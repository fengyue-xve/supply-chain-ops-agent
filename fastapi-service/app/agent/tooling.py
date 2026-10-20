"""工具边界：ToolPolicy（可见性与准入判定）+ ToolExecutor（预算预检/调用/规范化/事件）。

受 Pi prepareToolCall 与 beforeToolCall（未知工具 → 不触达外部效果的 immediate 错误结果；
拦截仍产生错误结果并继续循环）以及 tool_execution_start/end 事件思想启发。安全红线不变：
模型不可见写工具；未知/写工具/参数非法一律不触达 Java（写端点零触达）；Java 仍是数据与
权限边界。

事件口径：被拒工具记 `tool_blocked`（只进 Trace，不落阶段日志——保持五阶段日志口径）；
真实发起或预算预检短路记 `tool_call`（阶段日志 + Trace），Trace 只记参数键名与固定
outcome 类别，不记参数值与工具错误原文。
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

from app.agent.events import AgentEvent, EventSink
from app.agent.render import render_tool_result
from app.agent.run import RunContext
from app.ids import new_tool_call_id
from app.models.base import ToolCallRequest
from app.schemas import ToolCallRecord

# 与 JavaToolClient 超时文案一致：预算取消在途工具调用时复用同一降级语义。
TOOL_TIMEOUT_ERROR = "工具调用超时，请稍后重试"

_DECISION_UNKNOWN = "unknown_tool"
_DECISION_APPROVAL = "requires_approval"
_DECISION_PARSE = "parse_error"


def _ms_since(start: float) -> int:
    return int((time.monotonic() - start) * 1000)


@dataclass(frozen=True)
class ToolDecision:
    allowed: bool
    code: str | None = None
    message: str | None = None


@dataclass(frozen=True)
class ToolExecution:
    record: ToolCallRecord
    data: dict | None = None
    protocol_id: str = ""


class ToolPolicy:
    """可见性单一事实源：模型只看到只读且不要求审批的工具；执行准入在此判定。"""

    def __init__(self, tools: list[dict] | None) -> None:
        self._tools = tools if isinstance(tools, list) else []
        self._index = {
            item["name"]: item
            for item in self._tools
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        }

    def model_tools(self) -> list[dict]:
        return [
            item
            for item in self._tools
            if isinstance(item, dict)
            and item.get("readOnly") is True
            and item.get("requiresApproval") is not True
        ]

    def check(self, call: ToolCallRequest) -> ToolDecision:
        definition = self._index.get(call.name)
        if definition is None:
            return ToolDecision(False, _DECISION_UNKNOWN, f"未知工具：{call.name}")
        if not definition.get("readOnly", False) or definition.get("requiresApproval", False):
            return ToolDecision(False, _DECISION_APPROVAL, "该工具需要人工确认，当前未执行。")
        if call.parse_error:
            return ToolDecision(False, _DECISION_PARSE, call.parse_error)
        return ToolDecision(True)


class ToolExecutor:
    def __init__(
        self,
        policy: ToolPolicy,
        java_client,
        sink: EventSink,
        run: RunContext,
    ) -> None:
        self._policy = policy
        self._java_client = java_client
        self._sink = sink
        self._run = run

    async def run_call(
        self, call: ToolCallRequest, *, round_no: int, authorization: str | None
    ) -> ToolExecution:
        tool_call_id = new_tool_call_id()
        protocol_id = call.call_id or tool_call_id
        decision = self._policy.check(call)
        if not decision.allowed:
            self._sink.emit(
                AgentEvent(
                    type="tool_blocked",
                    request_id=self._run.request_id,
                    run_key=self._run.run_key,
                    fields={"round": round_no, "tool": call.name},
                    trace_fields={"reason": decision.code},
                )
            )
            return ToolExecution(
                record=ToolCallRecord(
                    tool_call_id=tool_call_id,
                    tool=call.name,
                    params=call.params,
                    ok=False,
                    round=round_no,
                    error=decision.message,
                ),
                protocol_id=protocol_id,
            )
        # 预算预检：不足则不发起（仍记 tool_call 阶段日志）；
        # 在途超预算由 wait_for 取消（停止等待），复用既有超时文案。
        call_start = time.monotonic()
        remaining = self._run.remaining_seconds
        if remaining <= 0:
            self._sink.emit(
                AgentEvent(
                    type="tool_call",
                    request_id=self._run.request_id,
                    run_key=self._run.run_key,
                    duration_ms=_ms_since(call_start),
                    fields={
                        "round": round_no,
                        "tool": call.name,
                        "ok": False,
                        "error_class": "BudgetExhausted",
                    },
                    trace_fields={"param_keys": sorted(call.params)},
                )
            )
            return ToolExecution(
                record=ToolCallRecord(
                    tool_call_id=tool_call_id,
                    tool=call.name,
                    params=call.params,
                    ok=False,
                    round=round_no,
                    error=TOOL_TIMEOUT_ERROR,
                ),
                protocol_id=protocol_id,
            )
        try:
            outcome = await asyncio.wait_for(
                self._java_client.call(
                    call.name,
                    call.params,
                    request_id=self._run.request_id,
                    authorization=authorization,
                ),
                timeout=remaining,
            )
        except asyncio.TimeoutError as exc:
            self._sink.emit(
                AgentEvent(
                    type="tool_call",
                    request_id=self._run.request_id,
                    run_key=self._run.run_key,
                    duration_ms=_ms_since(call_start),
                    fields={
                        "round": round_no,
                        "tool": call.name,
                        "ok": False,
                        "error_class": type(exc).__name__,
                    },
                    trace_fields={"param_keys": sorted(call.params)},
                )
            )
            return ToolExecution(
                record=ToolCallRecord(
                    tool_call_id=tool_call_id,
                    tool=call.name,
                    params=call.params,
                    ok=False,
                    round=round_no,
                    error=TOOL_TIMEOUT_ERROR,
                ),
                protocol_id=protocol_id,
            )
        self._sink.emit(
            AgentEvent(
                type="tool_call",
                request_id=self._run.request_id,
                run_key=self._run.run_key,
                duration_ms=_ms_since(call_start),
                fields={"round": round_no, "tool": call.name, "ok": outcome.ok},
                trace_fields={
                    "param_keys": sorted(call.params),
                    "outcome": "ok" if outcome.ok else "java_error",
                },
            )
        )
        if outcome.ok:
            return ToolExecution(
                ToolCallRecord(
                    tool_call_id=tool_call_id,
                    tool=call.name,
                    params=call.params,
                    ok=True,
                    round=round_no,
                    summary=render_tool_result(call.name, outcome.data),
                ),
                data=outcome.data,
                protocol_id=protocol_id,
            )
        return ToolExecution(
            record=ToolCallRecord(
                tool_call_id=tool_call_id,
                tool=call.name,
                params=call.params,
                ok=False,
                round=round_no,
                error=outcome.error,
            ),
            protocol_id=protocol_id,
        )
