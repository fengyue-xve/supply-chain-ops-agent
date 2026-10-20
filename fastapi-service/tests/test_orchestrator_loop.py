import asyncio

from app.agent.orchestrator import AgentOrchestrator
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.models.errors import ModelProviderError
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH


class SequenceProvider(ModelProvider):
    name = "sequence"

    def __init__(self, responses):
        self.responses = list(responses)
        self.messages = []

    async def complete(self, messages, tools=None):
        self.messages.append(messages)
        return self.responses.pop(0)


class FailingProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        raise ModelProviderError()


class FailingAfterToolProvider(SequenceProvider):
    async def complete(self, messages, tools=None):
        if len(self.messages) == 0:
            self.messages.append(messages)
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
        raise ModelProviderError()


class StubClient:
    def __init__(self):
        self.headers: list[dict] = []

    async def fetch_schema(self, authorization=None):
        from tests.conftest import schema_payload

        return schema_payload()["data"]

    async def call(self, name, params, request_id=None, authorization=None):
        from app.tools.java_client import ToolCallOutcome

        self.headers.append({"request_id": request_id, "authorization": authorization})
        return ToolCallOutcome(
            ok=True,
            data={
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
            },
        )


def make_orchestrator(provider, max_rounds=5):
    client = StubClient()
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    return AgentOrchestrator(provider, client, loader, max_rounds=max_rounds)


def test_loop_returns_model_final_text_and_raw_tool_data_in_next_context():
    provider = SequenceProvider(
        [
            ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="query-stock-level",
                        params={"skus": ["AUD-001"]},
                        call_id="call-1",
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(text="根据查询，AUD-001 当前库存偏低。"),
        ]
    )
    data = asyncio.run(make_orchestrator(provider).chat("AUD-001 库存？"))

    assert data.reply == "根据查询，AUD-001 当前库存偏低。"
    assert len(data.tool_calls) == 1
    assert data.tool_calls[0].round == 1
    next_messages = provider.messages[1]
    tool_message = next_messages[-1]
    assert tool_message["role"] == "tool"
    assert '"availableQty": 18' in tool_message["content"]
    assert '"stockLevel": "low"' in tool_message["content"]


def test_unknown_tool_is_blocked_by_schema_allowlist():
    provider = SequenceProvider(
        [
            ModelResponse(
                tool_calls=[ToolCallRequest(name="delete-everything", params={})],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(text="无法执行该操作。"),
        ]
    )
    data = asyncio.run(make_orchestrator(provider).chat("请清空数据"))

    assert data.tool_calls[0].ok is False
    assert "未知工具" in data.tool_calls[0].error


def test_model_failure_returns_fixed_reply_without_leaking_exception():
    data = asyncio.run(make_orchestrator(FailingProvider()).chat("AUD-001 库存？"))
    assert data.reply == "智能编排暂不可用，请稍后重试。"
    assert data.tool_calls == []


def test_model_failure_after_tool_keeps_completed_result():
    data = asyncio.run(
        make_orchestrator(FailingAfterToolProvider([])).chat("AUD-001 库存？")
    )

    assert data.tool_calls[0].ok is True
    assert "AUD-001" in data.reply
    assert "智能总结暂不可用" in data.reply


def test_loop_stops_at_configured_round_limit():
    provider = SequenceProvider(
        [
            ModelResponse(
                tool_calls=[ToolCallRequest(name="query-stock-level", params={})],
                assistant_message={"role": "assistant", "content": None},
            )
        ]
        * 5
    )
    data = asyncio.run(make_orchestrator(provider, max_rounds=2).chat("库存？"))

    assert len(data.tool_calls) == 2
    assert "轮数上限" in data.reply
    assert len(provider.responses) == 3


def test_loop_passes_same_authorization_and_request_id_to_every_call():
    provider = SequenceProvider(
        [
            ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="query-stock-level",
                        params={"skus": ["AUD-001"]},
                        call_id="call-1",
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="query-sales",
                        params={"sku": "AUD-001", "days": 7},
                        call_id="call-2",
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(text="两轮完成。"),
        ]
    )
    orchestrator = make_orchestrator(provider)
    data = asyncio.run(
        orchestrator.chat(
            "AUD-001 库存与销量",
            request_id="req-loop-000001",
            authorization="Bearer unit-admin-token-123",
        )
    )

    assert len(data.tool_calls) == 2
    headers = orchestrator._java_client.headers
    assert len(headers) == 2
    for entry in headers:
        assert entry == {
            "request_id": "req-loop-000001",
            "authorization": "Bearer unit-admin-token-123",
        }
