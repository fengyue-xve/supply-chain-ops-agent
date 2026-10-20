"""P1 结构化阶段日志测试：五个 stage 的稳定字段、降级路径 error_class、日志无敏感内容。"""

import asyncio
import logging

from app.agent.orchestrator import (
    MODEL_FAILURE_REPLY,
    SCHEMA_FAILURE_REPLY,
    AgentOrchestrator,
)
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.models.errors import ModelProviderError
from app.tools.java_client import ToolCallOutcome
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH

LOGGER_NAME = "app.agent.orchestrator"
SLOW_SECONDS = 10.0

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


class SchemaClient:
    async def fetch_schema(self, authorization=None):
        from tests.conftest import schema_payload

        return schema_payload()["data"]


class DataClient:
    def __init__(self, slow_tools=()):
        self.slow_tools = set(slow_tools)

    async def call(self, name, params, request_id=None, authorization=None):
        if name in self.slow_tools:
            await asyncio.sleep(SLOW_SECONDS)
        return ToolCallOutcome(ok=True, data=STOCK_DATA)


class OneToolProvider(ModelProvider):
    """单轮返回一个只读工具调用；无 assistant_message（D6 单轮组装语义）。"""

    async def complete(self, messages, tools=None):
        return ModelResponse(
            tool_calls=[
                ToolCallRequest(
                    name="query-stock-level", params={"skus": ["AUD-001"]}, call_id="c1"
                )
            ]
        )


class FailingProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        raise ModelProviderError()


class FailingLoader:
    """鸭子类型的契约加载器：load 抛 RuntimeError，命中 orchestrator 的降级路径。"""

    async def load(self, authorization=None):
        raise RuntimeError("契约不可用")


def make(provider, client, *, loader=None, budget=None):
    loader = loader or ToolSchemaLoader(SchemaClient(), snapshot_path=SNAPSHOT_PATH)
    kwargs = {} if budget is None else {"total_budget_seconds": budget}
    return AgentOrchestrator(provider, client, loader, **kwargs)


def stage_lines(caplog):
    return [
        record.getMessage()
        for record in caplog.records
        if record.name == LOGGER_NAME and record.getMessage().startswith("agent_stage")
    ]


def test_success_path_emits_five_stages_in_order_with_required_fields(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    data = asyncio.run(
        make(OneToolProvider(), DataClient()).chat("AUD-001 库存？", request_id="req-log-1")
    )

    lines = stage_lines(caplog)
    stages = [line.split("stage=")[1].split(" ")[0] for line in lines]
    assert stages == [
        "schema_load",
        "rag_retrieve",
        "model_call",
        "tool_call",
        "response_assemble",
    ]
    assert all("request_id=req-log-1" in line and "duration_ms=" in line for line in lines)
    assert "source=java" in lines[0]
    assert "chunks=0" in lines[1]
    assert "round=1" in lines[2] and "error_class=" not in lines[2]
    assert "round=1" in lines[3]
    assert "tool=query-stock-level" in lines[3] and "ok=True" in lines[3]
    assert data.tool_calls[0].ok is True


def test_schema_failure_logs_stage_with_error_class_and_stops(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    data = asyncio.run(
        make(FailingProvider(), DataClient(), loader=FailingLoader()).chat(
            "hi", request_id="req-log-2"
        )
    )

    assert data.reply == SCHEMA_FAILURE_REPLY
    lines = stage_lines(caplog)
    assert len(lines) == 1
    assert "stage=schema_load" in lines[0]
    assert "error_class=RuntimeError" in lines[0] and "request_id=req-log-2" in lines[0]


def test_model_failure_logs_stage_with_error_class(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    data = asyncio.run(
        make(FailingProvider(), DataClient()).chat("hi", request_id="req-log-3")
    )

    assert data.reply == MODEL_FAILURE_REPLY
    line = next(line for line in stage_lines(caplog) if "stage=model_call" in line)
    assert "round=1" in line
    assert "error_class=ModelProviderError" in line


def test_tool_timeout_logs_stage_with_error_class(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    asyncio.run(
        make(OneToolProvider(), DataClient(slow_tools={"query-stock-level"}), budget=0.3).chat(
            "AUD-001 库存？", request_id="req-log-4"
        )
    )

    line = next(line for line in stage_lines(caplog) if "stage=tool_call" in line)
    assert "round=1" in line and "tool=query-stock-level" in line
    assert "ok=False" in line
    assert "error_class=TimeoutError" in line


def test_stage_logs_never_contain_message_or_credentials(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    asyncio.run(
        make(OneToolProvider(), DataClient()).chat(
            "库存正文不应入日志-7f3a",
            request_id="req-log-5",
            authorization="Bearer tok-not-in-logs-9c1",
        )
    )

    text = caplog.text
    assert "req-log-5" in text
    assert "库存正文不应入日志-7f3a" not in text
    assert "tok-not-in-logs-9c1" not in text
    assert "Bearer" not in text
