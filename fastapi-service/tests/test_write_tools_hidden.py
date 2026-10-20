"""W3-③ 契约扩展回归：敏感写工具对模型不可见、且请求执行必被拒绝（双重防线）。

防线一：orchestrator 构造 model_tools 时按 readOnly=true 且 requiresApproval != true 过滤；
防线二：_execute 对非只读工具直接拒绝——即使模型被越权提示劫持仍报出工具名，也绝不触达 Java。
契约快照已扩到 5 工具（3 只读 + 2 写），本文件锁定写工具不会因此泄进模型可见列表。
"""

import asyncio

from app.agent.orchestrator import AgentOrchestrator
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload

WRITE_TOOL_NAMES = ("create-purchase-order", "confirm-po")


class SequenceTrackingProvider(ModelProvider):
    name = "sequence-tracking"

    def __init__(self, responses):
        self.responses = list(responses)
        self.tools_passed: list = []

    async def complete(self, messages, tools=None):
        self.tools_passed.append(tools)
        return self.responses.pop(0)


class RecordingClient:
    def __init__(self):
        self.calls: list[str] = []

    async def fetch_schema(self, authorization=None):
        return schema_payload()["data"]

    async def call(self, name, params, request_id=None, authorization=None):
        self.calls.append(name)
        return None


def _orchestrator(provider, client):
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    return AgentOrchestrator(provider, client, loader, max_rounds=5)


def test_write_tools_are_filtered_out_of_model_visible_list():
    provider = SequenceTrackingProvider([ModelResponse(text="无需工具。")])
    client = RecordingClient()
    asyncio.run(_orchestrator(provider, client).chat("你好"))

    names = [t["name"] for t in provider.tools_passed[0]]
    assert names == ["query-stock-level", "query-sales", "suggest-replenish"]
    for write_tool in WRITE_TOOL_NAMES:
        assert write_tool not in names


def test_write_tool_execution_is_refused_at_dispatch():
    for write_tool in WRITE_TOOL_NAMES:
        provider = SequenceTrackingProvider(
            [
                ModelResponse(
                    tool_calls=[
                        ToolCallRequest(
                            name=write_tool,
                            params={"items": [{"sku": "AUD-001", "qty": 48}]},
                        )
                    ],
                    assistant_message={"role": "assistant", "content": None},
                ),
                ModelResponse(text="该操作需要人工确认。"),
            ]
        )
        client = RecordingClient()
        data = asyncio.run(_orchestrator(provider, client).chat("帮我直接下采购单"))

        record = data.tool_calls[0]
        assert record.ok is False
        assert record.error == "该工具需要人工确认，当前未执行。"
        assert client.calls == [], f"{write_tool} 不应触达 Java 执行端点"
