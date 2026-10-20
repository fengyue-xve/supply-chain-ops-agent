import asyncio
import json

import httpx
import pytest

from app.models.deepseek import DeepSeekProvider, _to_function_tools
from app.models.errors import ModelProviderError


def run(coro):
    return asyncio.run(coro)


def test_provider_sends_openai_compatible_function_tools():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers["authorization"]
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                        "name": "query-stock-level",
                                        "arguments": '{"skus":["AUD-001"]}',
                                    },
                                }
                            ],
                        }
                    }
                ]
            },
        )

    provider = DeepSeekProvider(
        api_key="test-key",
        base_url="http://model.test/v1",
        transport=httpx.MockTransport(handler),
    )
    response = run(
        provider.complete(
            [{"role": "user", "content": "AUD-001 库存？"}],
            tools=[
                {
                    "name": "query-stock-level",
                    "description": "查库存",
                    "inputSchema": {"type": "object", "properties": {}},
                }
            ],
        )
    )
    run(provider.aclose())

    assert captured["auth"] == "Bearer test-key"
    assert captured["body"]["model"] == "deepseek-chat"
    assert captured["body"]["tool_choice"] == "auto"
    assert captured["body"]["tools"] == _to_function_tools(
        [
            {
                "name": "query-stock-level",
                "description": "查库存",
                "inputSchema": {"type": "object", "properties": {}},
            }
        ]
    )
    assert response.tool_calls[0].name == "query-stock-level"
    assert response.tool_calls[0].params == {"skus": ["AUD-001"]}
    assert response.tool_calls[0].call_id == "call-1"
    assert response.assistant_message["tool_calls"][0]["id"] == "call-1"


def test_provider_marks_invalid_arguments_without_crashing():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "tool_calls": [
                                {
                                    "id": "call-bad",
                                    "function": {
                                        "name": "query-sales",
                                        "arguments": "not-json",
                                    },
                                }
                            ],
                        }
                    }
                ]
            },
        )

    provider = DeepSeekProvider(
        api_key="test-key",
        base_url="http://model.test/v1",
        transport=httpx.MockTransport(handler),
    )
    response = run(provider.complete([{"role": "user", "content": "销量？"}]))
    run(provider.aclose())

    assert response.tool_calls[0].params == {}
    assert "合法 JSON" in response.tool_calls[0].parse_error


def test_provider_without_api_key_fails_with_fixed_error():
    provider = DeepSeekProvider(
        api_key="",
        base_url="http://model.test/v1",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(500, json={"error": "must not call"})
        ),
    )
    with pytest.raises(ModelProviderError):
        run(provider.complete([{"role": "user", "content": "test"}]))
    run(provider.aclose())
