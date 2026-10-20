"""P0 端到端总预算的确定性测试：小预算 + 慢假件（睡眠远大于预算），
以取消/预检查为断言对象，不用真实 25s 等待。

覆盖：总预算从 chat() 入口起算（契约加载也受约束）、在途调用取消、性能无关的
工具/RAG 预算预检查（含阶段日志）、AGENT_TOTAL_BUDGET_SECONDS 非法配置显式失败。
"""

import asyncio
import logging
import time

import pytest

from app.agent.orchestrator import (
    MODEL_FAILURE_REPLY,
    SCHEMA_FAILURE_REPLY,
    TOOL_TIMEOUT_ERROR,
    AgentOrchestrator,
)
from app.config import JAVA_GATEWAY_UPSTREAM_TIMEOUT_SECONDS, load_settings
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.tools.java_client import ToolCallOutcome
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload

# 远大于测试预算；正确实现下会被 wait_for 取消，不会真的等它结束。
SLOW_SECONDS = 10.0

LOGGER_NAME = "app.agent.orchestrator"

STOCK_DATA = {
    "items": [
        {
            "sku": "AUD-001",
            "productName": "无线耳机",
            "availableQty": 18,
            "stockLevel": "low",
            "snapDate": "2026-09-03",
        }
    ],
    "missingSkus": [],
    "total": 1,
}


class SlowProvider(ModelProvider):
    """任何一次模型调用都慢；用于「预算耗尽于模型调用」。"""

    def __init__(self):
        self.calls = 0

    async def complete(self, messages, tools=None):
        self.calls += 1
        await asyncio.sleep(SLOW_SECONDS)
        return ModelResponse(text="不该出现")


class ToolThenSlowProvider(ModelProvider):
    """第一轮立即返回一个工具调用；第二轮模型调用慢。"""

    def __init__(self):
        self.count = 0

    async def complete(self, messages, tools=None):
        self.count += 1
        if self.count == 1:
            return ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="query-stock-level",
                        params={"skus": ["AUD-001"]},
                        call_id="call-1",
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            )
        await asyncio.sleep(SLOW_SECONDS)
        return ModelResponse(text="不该出现")


class TwoToolsProvider(ModelProvider):
    """单轮返回两个只读工具调用；无 assistant_message（D6 单轮组装语义）。"""

    async def complete(self, messages, tools=None):
        return ModelResponse(
            tool_calls=[
                ToolCallRequest(
                    name="query-stock-level", params={"skus": ["AUD-001"]}, call_id="c1"
                ),
                ToolCallRequest(
                    name="query-sales", params={"sku": "AUD-001", "days": 7}, call_id="c2"
                ),
            ]
        )


class ClockJumpingTwoToolsProvider(ModelProvider):
    """返回两个只读工具调用，并把假时钟推过预算（模型阶段耗尽剩余预算）。"""

    def __init__(self, clock):
        self._clock = clock

    async def complete(self, messages, tools=None):
        self._clock.now = 99.0
        return ModelResponse(
            tool_calls=[
                ToolCallRequest(
                    name="query-stock-level", params={"skus": ["AUD-001"]}, call_id="c1"
                ),
                ToolCallRequest(
                    name="query-sales", params={"sku": "AUD-001", "days": 7}, call_id="c2"
                ),
            ]
        )


class BudgetClient:
    """schema 立即返回；slow_tools 中的工具会睡眠（其余立即成功）。"""

    def __init__(self, slow_tools=()):
        self.slow_tools = set(slow_tools)
        self.calls: list[str] = []

    async def fetch_schema(self, authorization=None):
        return schema_payload()["data"]

    async def call(self, name, params, request_id=None, authorization=None):
        self.calls.append(name)
        if name in self.slow_tools:
            await asyncio.sleep(SLOW_SECONDS)
        return ToolCallOutcome(ok=True, data=STOCK_DATA)


class SlowSchemaClient:
    """fetch_schema 睡眠远大于预算；正确实现下会被取消，不会真的等它结束。"""

    def __init__(self):
        self.schema_calls = 0

    async def fetch_schema(self, authorization=None):
        self.schema_calls += 1
        await asyncio.sleep(SLOW_SECONDS)
        return schema_payload()["data"]


class _FakeClock:
    """仅替换 orchestrator 模块内的 time.monotonic，不触碰 asyncio 自己的计时。

    测试通过改写 now 推进时间，不依赖被测代码内部 monotonic 的调用次数。
    """

    def __init__(self, now: float = 0.0):
        self.now = now

    def monotonic(self) -> float:
        return self.now


class ClockJumpingLoader:
    """鸭子类型契约加载器：返回契约的同时把假时钟推过预算。"""

    def __init__(self, clock):
        self._clock = clock

    async def load(self, authorization=None):
        self._clock.now = 99.0
        return schema_payload()["data"]


class SpyRetriever:
    """记录 search 是否被调用；用于断言预算耗尽时检索被跳过。"""

    def __init__(self):
        self.searches = 0

    def search(self, query, request_id=""):
        self.searches += 1
        return []


def make(provider, client, budget):
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    return AgentOrchestrator(provider, client, loader, total_budget_seconds=budget)


def stage_lines(caplog):
    return [
        record.getMessage()
        for record in caplog.records
        if record.name == LOGGER_NAME and record.getMessage().startswith("agent_stage")
    ]


def test_budget_exhausted_on_model_call_returns_failure_reply_within_budget():
    provider = SlowProvider()
    client = BudgetClient()
    start = time.monotonic()
    data = asyncio.run(make(provider, client, budget=0.3).chat("AUD-001 库存？"))
    elapsed = time.monotonic() - start

    assert data.reply == MODEL_FAILURE_REPLY
    assert data.tool_calls == []
    assert provider.calls == 1
    assert client.calls == []
    # 在途模型调用被取消：总耗时≈预算（10s 睡眠没有发生），远小于 SLOW_SECONDS。
    assert elapsed < 0.3 + 0.7


def test_zero_budget_short_circuits_at_schema_stage():
    """预算为 0：契约阶段即返回既有 schema 降级文案，模型与工具零调用。"""
    provider = SlowProvider()
    client = BudgetClient()
    data = asyncio.run(make(provider, client, budget=0.0).chat("库存？"))

    assert data.reply == SCHEMA_FAILURE_REPLY
    assert data.tool_calls == []
    assert provider.calls == 0
    assert client.calls == []


def test_budget_exhausted_on_schema_load_returns_schema_failure_within_budget():
    """慢契约加载被预算取消：schema 降级文案，总耗时≈预算，模型/工具零调用。"""
    provider = SlowProvider()
    client = SlowSchemaClient()
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    start = time.monotonic()
    data = asyncio.run(
        AgentOrchestrator(provider, client, loader, total_budget_seconds=0.3).chat(
            "AUD-001 库存？"
        )
    )
    elapsed = time.monotonic() - start

    assert data.reply == SCHEMA_FAILURE_REPLY
    assert data.tool_calls == []
    assert client.schema_calls == 1  # 已发起但被取消（10s 睡眠没有发生）
    assert provider.calls == 0
    assert elapsed < 0.3 + 0.7


def test_budget_exhausted_on_second_model_call_keeps_completed_tool_results():
    provider = ToolThenSlowProvider()
    client = BudgetClient()
    start = time.monotonic()
    data = asyncio.run(make(provider, client, budget=0.5).chat("AUD-001 库存？"))
    elapsed = time.monotonic() - start

    assert client.calls == ["query-stock-level"]
    assert len(data.tool_calls) == 1 and data.tool_calls[0].ok is True
    assert "AUD-001" in data.reply
    assert "智能总结暂不可用" in data.reply
    assert elapsed < 0.5 + 1.0


def test_budget_exhausted_mid_tools_cancels_remaining_and_keeps_first_result():
    provider = TwoToolsProvider()
    client = BudgetClient(slow_tools={"query-sales"})
    start = time.monotonic()
    data = asyncio.run(make(provider, client, budget=0.5).chat("AUD-001 库存与销量"))
    elapsed = time.monotonic() - start

    assert client.calls == ["query-stock-level", "query-sales"]  # 第二个已发起但被取消
    assert [r.ok for r in data.tool_calls] == [True, False]
    assert data.tool_calls[1].error == TOOL_TIMEOUT_ERROR
    assert "AUD-001" in data.reply
    assert TOOL_TIMEOUT_ERROR in data.reply
    assert elapsed < 0.5 + 1.0


def test_tool_precheck_skips_call_when_budget_already_spent(monkeypatch):
    """剩余预算 <= 0 时不发起工具调用（模型返回后把假时钟推过预算）。"""
    clock = _FakeClock(0.0)
    provider = ClockJumpingTwoToolsProvider(clock)
    client = BudgetClient()
    orchestrator = make(provider, client, budget=1.0)
    monkeypatch.setattr("app.agent.orchestrator.time", clock)
    data = asyncio.run(orchestrator.chat("AUD-001 库存与销量"))

    assert client.calls == []  # 两个工具都未发起（Java 未触达）
    assert [r.ok for r in data.tool_calls] == [False, False]
    assert {r.error for r in data.tool_calls} == {TOOL_TIMEOUT_ERROR}
    assert TOOL_TIMEOUT_ERROR in data.reply


def test_tool_precheck_logs_budget_exhausted_without_sensitive_content(monkeypatch, caplog):
    """因预算未发起的工具调用仍输出 tool_call 阶段日志：error_class=BudgetExhausted，
    且不含凭证、用户消息与参数内容。"""
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    clock = _FakeClock(0.0)
    provider = ClockJumpingTwoToolsProvider(clock)
    client = BudgetClient()
    orchestrator = make(provider, client, budget=1.0)
    monkeypatch.setattr("app.agent.orchestrator.time", clock)
    asyncio.run(
        orchestrator.chat(
            "库存正文不应入日志-4b1c",
            request_id="req-budget-log",
            authorization="Bearer tok-budget-not-in-logs",
        )
    )

    tool_lines = [line for line in stage_lines(caplog) if "stage=tool_call" in line]
    assert len(tool_lines) == 2
    assert "tool=query-stock-level" in tool_lines[0]
    assert "tool=query-sales" in tool_lines[1]
    for line in tool_lines:
        assert "request_id=req-budget-log" in line
        assert "duration_ms=" in line
        assert "round=1" in line
        assert "ok=False" in line
        assert "error_class=BudgetExhausted" in line
    text = caplog.text
    assert "tok-budget-not-in-logs" not in text
    assert "库存正文不应入日志-4b1c" not in text
    assert "AUD-001" not in text  # 工具参数内容不入日志


def test_budget_exhausted_before_rag_skips_retrieval_and_model(monkeypatch, caplog):
    """契约加载耗尽预算后：RAG 跳过（不检索、不注入）、模型不发起，走既有降级文案。"""
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    clock = _FakeClock(0.0)
    provider = SlowProvider()
    client = BudgetClient()
    loader = ClockJumpingLoader(clock)
    retriever = SpyRetriever()
    orchestrator = AgentOrchestrator(
        provider, client, loader, retriever=retriever, total_budget_seconds=1.0
    )
    monkeypatch.setattr("app.agent.orchestrator.time", clock)
    data = asyncio.run(orchestrator.chat("AUD-001 库存？", request_id="req-rag-budget"))

    assert retriever.searches == 0
    assert provider.calls == 0
    assert data.reply == MODEL_FAILURE_REPLY
    lines = stage_lines(caplog)
    rag_line = next(line for line in lines if "stage=rag_retrieve" in line)
    assert "chunks=0" in rag_line and "error_class=BudgetExhausted" in rag_line
    assert not [line for line in lines if "stage=model_call" in line]


def test_config_rejects_non_positive_or_non_numeric_budget(monkeypatch):
    for raw in ("0", "-5", "abc"):
        monkeypatch.setenv("AGENT_TOTAL_BUDGET_SECONDS", raw)
        with pytest.raises(ValueError, match="AGENT_TOTAL_BUDGET_SECONDS"):
            load_settings()


def test_config_rejects_budget_at_or_above_java_gateway_timeout(monkeypatch):
    for raw in ("60", str(JAVA_GATEWAY_UPSTREAM_TIMEOUT_SECONDS)):
        monkeypatch.setenv("AGENT_TOTAL_BUDGET_SECONDS", raw)
        with pytest.raises(ValueError, match="AGENT_TOTAL_BUDGET_SECONDS"):
            load_settings()


def test_config_accepts_default_and_valid_budget(monkeypatch):
    monkeypatch.delenv("AGENT_TOTAL_BUDGET_SECONDS", raising=False)
    assert load_settings().agent_total_budget_seconds == 25.0
    monkeypatch.setenv("AGENT_TOTAL_BUDGET_SECONDS", "10.5")
    assert load_settings().agent_total_budget_seconds == 10.5
