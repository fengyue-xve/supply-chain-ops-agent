"""A. Harness 结构单元测试：ContextBuilder 消息顺序、RunContext 停止原因归口、
ToolPolicy 决策、ToolExecutor 拒绝零触达、ModelGateway 错误映射（含超时）。

这些测试锁定「受 Pi 设计启发的 Python 实现」的边界行为；端到端语义仍由既有
test_stage_logs / test_total_budget / test_orchestrator_loop 等锁定。
"""

import asyncio
import time
from datetime import datetime, timezone

from app.agent.context import (
    REFERENCE_FOOTER,
    REFERENCE_HEADER,
    SYSTEM_PROMPT,
    build_messages,
    reference_message,
    source_refs,
)
from app.agent.gateway import ModelGateway
from app.agent.run import (
    STOP_ANSWERED,
    STOP_BUDGET_EXHAUSTED,
    STOP_ROUND_LIMIT,
    STOP_UNKNOWN,
    RunContext,
)
from app.agent.tooling import TOOL_TIMEOUT_ERROR, ToolExecutor, ToolPolicy
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.models.errors import ModelProviderError
from app.rag.documents import KnowledgeChunk
from app.schemas import HistoryMessage
from app.tools.java_client import ToolCallOutcome

READ_TOOL = {"name": "query-stock-level", "readOnly": True, "requiresApproval": False}
WRITE_TOOL = {"name": "create-purchase-order", "readOnly": False, "requiresApproval": True}


def _run(**overrides) -> RunContext:
    now = time.monotonic()
    values = dict(
        request_id="req-harness",
        conversation_id="conv-harness",
        started_monotonic=now,
        deadline=now + 25.0,
        total_budget_seconds=25.0,
        max_rounds=5,
        started_wall=datetime.now(timezone.utc).isoformat(timespec="seconds"),
    )
    values.update(overrides)
    return RunContext(**values)


def _chunk(chunk_id: str = "supply-ops.zh#stock-level-1") -> KnowledgeChunk:
    return KnowledgeChunk(
        doc="供应链运营知识",
        doc_id="supply-ops.zh",
        section_id="stock-level",
        title="库存水平",
        part=1,
        chunk_id=chunk_id,
        text="库存水平说明正文。",
        source_path="supply-ops.zh.md",
    )


class RecordingSink:
    def __init__(self):
        self.events: list = []

    def begin(self, run) -> None:
        self.events.append(("begin", run.request_id))

    def emit(self, event) -> None:
        self.events.append(event)

    def end(self, run) -> None:
        self.events.append(("end", run.request_id))


class RecordingJavaClient:
    def __init__(self):
        self.calls: list[str] = []

    async def call(self, name, params, request_id=None, authorization=None):
        self.calls.append(name)
        return ToolCallOutcome(ok=True, data={})


# ---------------------------------------------------------------- ContextBuilder


def test_build_messages_without_history_or_chunks_is_legacy_elementwise():
    assert build_messages("hi") == [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "hi"},
    ]


def test_build_messages_orders_system_history_reference_user():
    history = [
        HistoryMessage(role="user", content="问题"),
        HistoryMessage(role="assistant", content="回答"),
    ]
    messages = build_messages("继续", history=history, reference_chunks=[_chunk()])
    assert messages[0] == {"role": "system", "content": SYSTEM_PROMPT}
    assert messages[1:3] == [
        {"role": "user", "content": "问题"},
        {"role": "assistant", "content": "回答"},
    ]
    reference = messages[3]
    assert reference["role"] == "user"
    assert reference["content"].startswith(REFERENCE_HEADER)
    assert REFERENCE_FOOTER in reference["content"]
    assert "supply-ops.zh#stock-level-1" in reference["content"]
    assert messages[4] == {"role": "user", "content": "继续"}


def test_reference_message_and_source_refs_are_none_without_hits():
    assert reference_message([]) is None
    assert source_refs([]) is None


def test_source_refs_only_from_injected_chunks():
    refs = source_refs([_chunk(), _chunk("supply-ops.zh#stock-level-2")])
    assert [ref.chunk_id for ref in refs] == [
        "supply-ops.zh#stock-level-1",
        "supply-ops.zh#stock-level-2",
    ]


# -------------------------------------------------------------------- RunContext


def test_run_context_remaining_seconds_uses_injected_clock():
    run = _run(clock=lambda: 4.0, deadline=10.0)
    assert run.remaining_seconds == 6.0


def test_run_finish_maps_unknown_and_degraded():
    answered = _run()
    answered.stop_reason = STOP_ANSWERED
    answered.finish()
    assert answered.status == "ok"

    exhausted = _run()
    exhausted.stop_reason = STOP_BUDGET_EXHAUSTED
    exhausted.finish()
    assert exhausted.status == "degraded"

    limited = _run()
    limited.stop_reason = STOP_ROUND_LIMIT
    limited.finish()
    assert limited.status == "degraded"

    unmarked = _run()
    unmarked.finish()
    assert unmarked.stop_reason == STOP_UNKNOWN and unmarked.status == "error"


# --------------------------------------------------------------------- ToolPolicy


def test_model_tools_filters_write_and_approval_tools():
    policy = ToolPolicy([READ_TOOL, WRITE_TOOL, {"name": "x"}])
    assert [tool["name"] for tool in policy.model_tools()] == ["query-stock-level"]


def test_policy_decision_codes_and_fixed_messages():
    policy = ToolPolicy([READ_TOOL, WRITE_TOOL])
    unknown = policy.check(ToolCallRequest(name="delete-everything", params={}))
    assert not unknown.allowed and unknown.code == "unknown_tool"
    assert "未知工具" in unknown.message

    approval = policy.check(ToolCallRequest(name="create-purchase-order", params={}))
    assert not approval.allowed and approval.code == "requires_approval"
    assert approval.message == "该工具需要人工确认，当前未执行。"

    parse = policy.check(
        ToolCallRequest(name="query-stock-level", params={}, parse_error="参数不是合法 JSON")
    )
    assert not parse.allowed and parse.code == "parse_error"
    assert parse.message == "参数不是合法 JSON"

    ok = policy.check(ToolCallRequest(name="query-stock-level", params={"skus": ["A1"]}))
    assert ok.allowed and ok.code is None


# ------------------------------------------------------------------- ToolExecutor


def test_executor_blocked_write_tool_never_touches_java_and_only_traces():
    sink = RecordingSink()
    client = RecordingJavaClient()
    run = _run()
    executor = ToolExecutor(ToolPolicy([READ_TOOL, WRITE_TOOL]), client, sink, run)

    execution = asyncio.run(
        executor.run_call(
            ToolCallRequest(name="create-purchase-order", params={"items": []}),
            round_no=1,
            authorization="Bearer unit-token",
        )
    )

    assert client.calls == []
    assert execution.record.ok is False
    assert execution.record.error == "该工具需要人工确认，当前未执行。"
    assert execution.data is None
    blocked = [e for e in sink.events if getattr(e, "type", "") == "tool_blocked"]
    assert len(blocked) == 1
    assert blocked[0].fields == {"round": 1, "tool": "create-purchase-order"}
    assert blocked[0].trace_fields == {"reason": "requires_approval"}
    assert not [e for e in sink.events if getattr(e, "type", "") == "tool_call"]


def test_executor_budget_precheck_skips_dispatch_with_timeout_semantics():
    sink = RecordingSink()
    client = RecordingJavaClient()
    run = _run(deadline=time.monotonic() - 1.0)
    executor = ToolExecutor(ToolPolicy([READ_TOOL]), client, sink, run)

    execution = asyncio.run(
        executor.run_call(
            ToolCallRequest(name="query-stock-level", params={"skus": ["A1"]}),
            round_no=2,
            authorization=None,
        )
    )

    assert client.calls == []
    assert execution.record.ok is False and execution.record.error == TOOL_TIMEOUT_ERROR
    calls = [e for e in sink.events if getattr(e, "type", "") == "tool_call"]
    assert len(calls) == 1
    assert calls[0].fields["error_class"] == "BudgetExhausted"


# ------------------------------------------------------------------- ModelGateway


class ErrorProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        raise ModelProviderError()


class SlowProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        await asyncio.sleep(10.0)
        return ModelResponse(text="never")


def test_gateway_maps_provider_error_to_error_class():
    outcome = asyncio.run(ModelGateway(ErrorProvider()).complete([], None, timeout=1.0))
    assert outcome.response is None and outcome.error_class == "ModelProviderError"


def test_gateway_maps_timeout_to_error_class():
    outcome = asyncio.run(ModelGateway(SlowProvider()).complete([], None, timeout=0.05))
    assert outcome.response is None and outcome.error_class == "TimeoutError"


class TextProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        return ModelResponse(text="ok")


def test_gateway_returns_response_on_success():
    outcome = asyncio.run(ModelGateway(TextProvider()).complete([], None, timeout=1.0))
    assert outcome.error_class is None and outcome.response.text == "ok"
