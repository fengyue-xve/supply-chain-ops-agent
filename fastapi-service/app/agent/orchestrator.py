"""受限 Agent Loop：模型选择只读工具，工具结果回传模型，最多循环 N 轮。

Harness 结构（受 Pi Agent Core 的 runLoop/事件总线/工具策略边界思想启发的 Python 最小
实现，非 Pi 运行时集成）：RunContext（app.agent.run）持有 request_id/conversation_id/
总预算 deadline/轮次与结束状态；消息组装走 app.agent.context.build_messages；模型调用经
app.agent.gateway.ModelGateway 隔离并把供应商错误编码为 error_class；工具可见性与执行
由 app.agent.tooling.ToolPolicy/ToolExecutor 收口；阶段日志与 Trace 经 app.agent.events
的 EventSink 汇聚。安全红线不变：模型不可见写工具；未知/写工具/参数非法一律不触达 Java。

FakeModelProvider 没有 assistant tool-call 消息，因此保持 D6 的一次调用行为；真实
Provider 会带 assistant_message，才进入下一轮。这让 D6 既不回归，又能验证 D7 协议。

端到端总预算（默认 25s，config 强制校验小于 Java 网关上游超时）：从 chat() 入口起算，
schema 加载/RAG/模型/工具调用共用同一 deadline——schema 与在途模型/工具调用以剩余
预算为硬超时（asyncio.wait_for 超时即取消），RAG 为进程内同步检索、预算耗尽时跳过；
预算耗尽复用既有降级语义（schema/模型失败文案 / 工具超时记录），不编造任何数据。

取消语义：chat() 任务被取消（CancelledError）时显式记录 stop_reason=cancelled 后原样
抛出；finally 仍完成 Trace 收口（同步收尾，进程被 SIGKILL 时无记录保证）。

结构化阶段日志：schema_load / rag_retrieve / model_call / tool_call / response_assemble
每阶段一条 INFO（稳定前缀 agent_stage，字段 request_id/stage/duration_ms，模型与工具轮次带
round，异常带 error_class）；只记标识、轮次与耗时，绝不记 Authorization、用户消息正文、
完整历史或工具参数。被拒工具（未知/写工具/参数非法）不落阶段日志，只进 Trace。

导入面兼容：SYSTEM_PROMPT/REFERENCE_HEADER/REFERENCE_FOOTER（app.agent.context）、
TOOL_TIMEOUT_ERROR（app.agent.tooling）在此 re-export；评测与测试按旧路径导入这些名字。
"""

import asyncio
import json
import logging
import time
from datetime import datetime, timezone
from typing import Any

from app.agent.context import (
    REFERENCE_FOOTER,
    REFERENCE_HEADER,
    SYSTEM_PROMPT,
    build_messages,
    source_refs,
)
from app.agent.events import AgentEvent, EventSink, StageLogSink
from app.agent.gateway import ModelGateway
from app.agent.run import (
    STOP_ANSWERED,
    STOP_BUDGET_EXHAUSTED,
    STOP_CANCELLED,
    STOP_MODEL_ERROR,
    STOP_ROUND_LIMIT,
    STOP_SCHEMA_FAILURE,
    STOP_TOOL_ASSEMBLED,
    RunContext,
)
from app.agent.tooling import TOOL_TIMEOUT_ERROR, ToolExecutor, ToolPolicy
from app.agent.live_trace import context_layout
from app.errors import ApiError
from app.ids import new_conversation_id, new_request_id, new_tool_call_id
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.rag.documents import KnowledgeChunk
from app.rag.retriever import EmptyRetriever
from app.schemas import ChatResponseData, HistoryMessage, ToolCallRecord

logger = logging.getLogger("app.agent.orchestrator")

MODEL_FAILURE_REPLY = "智能编排暂不可用，请稍后重试。"
SCHEMA_FAILURE_REPLY = "工具契约暂不可用，请稍后重试。"
MAX_SAFE_ROUNDS = 5
DEFAULT_TOTAL_BUDGET_SECONDS = 25.0


def _ms_since(start: float) -> int:
    return int((time.monotonic() - start) * 1000)


class AgentOrchestrator:
    def __init__(
        self,
        provider: ModelProvider,
        java_client,
        schema_loader,
        retriever=None,
        max_rounds: int = 5,
        total_budget_seconds: float = DEFAULT_TOTAL_BUDGET_SECONDS,
        event_sink: EventSink | None = None,
    ) -> None:
        self._java_client = java_client
        self._schema_loader = schema_loader
        self._retriever = retriever if retriever is not None else EmptyRetriever()
        self._max_rounds = max(1, min(max_rounds, MAX_SAFE_ROUNDS))
        self._total_budget_seconds = max(0.0, float(total_budget_seconds))
        self._sink = event_sink if event_sink is not None else StageLogSink()
        self._gateway = ModelGateway(provider)

    async def chat(
        self,
        message: str,
        conversation_id: str | None = None,
        request_id: str | None = None,
        authorization: str | None = None,
        history: list[HistoryMessage] | None = None,
    ) -> ChatResponseData:
        text = (message or "").strip()
        if not text:
            raise ApiError(400, "message 不能为空")

        conversation = conversation_id or new_conversation_id()
        request_id = request_id or new_request_id()
        # 调用方承诺已提供用户上下文(HTTP 入口无 Authorization 已被 api/chat 拦 401);
        # 本层只把凭证透传,不解析、不判定,权限边界仍在 Java。
        # 总预算从 chat() 入口起算：schema/RAG/模型/工具共用同一 deadline。
        start = time.monotonic()
        run = RunContext(
            request_id=request_id,
            conversation_id=conversation,
            started_monotonic=start,
            deadline=start + self._total_budget_seconds,
            total_budget_seconds=self._total_budget_seconds,
            max_rounds=self._max_rounds,
            started_wall=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            clock=time.monotonic,
        )
        records: list[ToolCallRecord] = []
        reference_chunks: list[KnowledgeChunk] = []
        self._sink.begin(run)
        try:
            schema_start = time.monotonic()
            try:
                # 以剩余预算为硬超时（剩余 <=0 时 wait_for 直接取消，load 不会实际执行）。
                schema = await asyncio.wait_for(
                    self._schema_loader.load(authorization=authorization),
                    timeout=run.remaining_seconds,
                )
            except asyncio.TimeoutError as exc:
                # 预算耗尽于契约加载：契约不可用，复用既有 schema 降级文案。
                run.stop_reason = STOP_BUDGET_EXHAUSTED
                self._emit_stage(
                    run, "schema_load", schema_start, error_class=type(exc).__name__
                )
                return ChatResponseData(
                    request_id=request_id,
                    conversation_id=conversation,
                    reply=SCHEMA_FAILURE_REPLY,
                    tool_calls=[],
                )
            except (RuntimeError, ValueError, KeyError, TypeError) as exc:
                run.stop_reason = STOP_SCHEMA_FAILURE
                self._emit_stage(
                    run, "schema_load", schema_start, error_class=type(exc).__name__
                )
                return ChatResponseData(
                    request_id=request_id,
                    conversation_id=conversation,
                    reply=SCHEMA_FAILURE_REPLY,
                    tool_calls=[],
                )
            run.schema_source = getattr(self._schema_loader, "source", None)
            self._emit_stage(run, "schema_load", schema_start, source=run.schema_source)

            tools = schema.get("tools") if isinstance(schema, dict) else None
            policy = ToolPolicy(tools)
            model_tools = policy.model_tools()
            run.model_tools_count = len(model_tools)
            reference_chunks = self._retrieve_chunks(text, run)
            messages = build_messages(text, history, reference_chunks)
            run.context_layers = context_layout(history, reference_chunks, messages)
            executor = ToolExecutor(policy, self._java_client, self._sink, run)
            final_text: str | None = None

            for round_no in range(1, run.max_rounds + 1):
                run.rounds_used = round_no
                remaining = run.remaining_seconds
                if remaining <= 0:
                    run.stop_reason = STOP_BUDGET_EXHAUSTED
                    final_text = self._model_failure_reply(records)
                    break
                model_start = time.monotonic()
                outcome = await self._gateway.complete(
                    messages, model_tools, timeout=remaining
                )
                if outcome.error_class is not None:
                    run.stop_reason = STOP_MODEL_ERROR
                    self._emit_stage(
                        run, "model_call", model_start, round=round_no,
                        error_class=outcome.error_class,
                    )
                    final_text = self._model_failure_reply(records)
                    break
                model = outcome.response
                self._emit_stage(
                    run, "model_call", model_start, round=round_no,
                    trace_fields={
                        "tool_names": [call.name for call in model.tool_calls or []],
                    },
                )

                if not model.tool_calls:
                    run.stop_reason = STOP_ANSWERED
                    final_text = model.text or self._assemble_reply(records)
                    break

                loop_calls = [
                    ToolCallRequest(
                        name=call.name,
                        params=call.params,
                        call_id=call.call_id or new_tool_call_id(),
                        parse_error=call.parse_error,
                    )
                    for call in model.tool_calls
                ]
                if model.assistant_message is not None:
                    messages.append(_assistant_message_for_loop(model, loop_calls))

                for call in loop_calls:
                    execution = await executor.run_call(
                        call, round_no=round_no, authorization=authorization
                    )
                    records.append(execution.record)
                    if model.assistant_message is not None:
                        messages.append(
                            _tool_message(
                                call,
                                execution.record,
                                execution.data,
                                execution.protocol_id,
                            )
                        )

                # Fake/D6 provider intentionally stops after one deterministic tool call.
                if model.assistant_message is None:
                    run.stop_reason = STOP_TOOL_ASSEMBLED
                    final_text = self._assemble_reply(records)
                    break
                if round_no == run.max_rounds:
                    run.stop_reason = STOP_ROUND_LIMIT
                    final_text = self._assemble_reply(records) + "\n（已达到工具调用轮数上限，请根据以上结果判断。）"

            reply = final_text or self._assemble_reply(records) or MODEL_FAILURE_REPLY
            self._emit_stage(
                run, "response_assemble", start,
                trace_fields={"tool_calls": len(records), "reply_chars": len(reply)},
            )
            return ChatResponseData(
                request_id=request_id,
                conversation_id=conversation,
                reply=reply,
                tool_calls=records,
                sources=source_refs(reference_chunks),
            )
        except asyncio.CancelledError:
            # 任务被取消（上层 wait_for 超时 / 停机 / 连接中断）：显式记录后原样抛出；
            # finally 仍收口——同步收尾无 await，取消不会打断 Trace 落盘（SIGKILL 除外）。
            run.stop_reason = STOP_CANCELLED
            raise
        finally:
            run.tool_call_count = len(records)
            run.sources_ids = [chunk.chunk_id for chunk in reference_chunks]
            run.finish()
            self._sink.end(run)

    def _emit_stage(
        self,
        run: RunContext,
        event_type: str,
        start: float,
        *,
        trace_fields: dict[str, Any] | None = None,
        **fields: Any,
    ) -> None:
        """五阶段事件发往 EventSink：StageLogSink 落既有格式日志，Trace 记录附加诊断字段。"""
        self._sink.emit(
            AgentEvent(
                type=event_type,
                request_id=run.request_id,
                run_key=run.run_key,
                duration_ms=_ms_since(start),
                fields=dict(fields),
                trace_fields=dict(trace_fields or {}),
            )
        )

    def _retrieve_chunks(self, text: str, run: RunContext) -> list[KnowledgeChunk]:
        """检索异常降级为原链路（零注入），只记录异常类型，不记录用户全文。

        检索为进程内同步调用（无 I/O，无法被取消）；预算已耗尽时跳过，不检索也不注入。
        Trace 只记 chunk_id/分数/是否实际注入等诊断字段，不记 query 正文与 chunk 正文。
        """
        start = time.monotonic()
        if run.remaining_seconds <= 0:
            self._emit_stage(
                run, "rag_retrieve", start,
                chunks=0, error_class="BudgetExhausted",
                trace_fields={"retriever_status": "skipped_budget"},
            )
            return []
        try:
            hits = self._retriever.search(text, request_id=run.request_id)
        except Exception as exc:
            logger.warning(
                "rag_retrieve_failed request_id=%s error=%s",
                run.request_id,
                type(exc).__name__,
            )
            self._emit_stage(
                run, "rag_retrieve", start,
                error_class=type(exc).__name__,
                trace_fields={"retriever_status": "error"},
            )
            return []
        selected = self._retriever.select_for_prompt(hits)
        self._emit_stage(
            run, "rag_retrieve", start,
            chunks=len(selected),
            trace_fields={
                "hit_ids": [hit.chunk.chunk_id for hit in hits],
                "hit_scores": [hit.score for hit in hits],
                "injected_ids": [chunk.chunk_id for chunk in selected],
                "corpus_chunks": len(getattr(self._retriever, "chunks", ()) or ()),
                "retriever_status": getattr(self._retriever, "status", "ok") or "ok",
            },
        )
        return selected

    @staticmethod
    def _assemble_reply(records: list[ToolCallRecord]) -> str:
        parts = [r.summary for r in records if r.ok and r.summary]
        errors = [r.error for r in records if not r.ok and r.error]
        reply = "\n".join(parts)
        if errors:
            suffix = "；".join(errors)
            reply = f"{reply}\n（{suffix}）" if reply else suffix
        return reply

    @staticmethod
    def _model_failure_reply(records: list[ToolCallRecord]) -> str:
        known = AgentOrchestrator._assemble_reply(records)
        if known:
            return f"{known}\n（智能总结暂不可用，以上为已完成的工具查询结果。）"
        return MODEL_FAILURE_REPLY


def _assistant_message_for_loop(
    model: ModelResponse, calls: list[ToolCallRequest]
) -> dict:
    """仅保留下一轮协议需要的字段，不把供应商的额外字段带入上下文。"""

    message = model.assistant_message or {}
    tool_calls = []
    for call in calls:
        tool_calls.append(
            {
                "id": call.call_id,
                "type": "function",
                "function": {
                    "name": call.name,
                    "arguments": json.dumps(call.params, ensure_ascii=False),
                },
            }
        )
    result = {"role": "assistant", "content": message.get("content")}
    if tool_calls:
        result["tool_calls"] = tool_calls
    return result


def _tool_message(
    call: ToolCallRequest,
    record: ToolCallRecord,
    data: dict[str, Any] | None,
    protocol_id: str,
) -> dict:
    content = {
        "ok": record.ok,
        "data": data if record.ok else None,
        "error": record.error,
    }
    return {
        "role": "tool",
        "tool_call_id": protocol_id,
        "content": json.dumps(content, ensure_ascii=False),
    }
