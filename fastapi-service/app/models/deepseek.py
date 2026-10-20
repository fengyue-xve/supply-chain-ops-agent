"""DeepSeek 的 OpenAI 兼容 Chat Completions Provider。

Provider 只负责协议适配，不负责工具白名单、轮数限制和业务结果渲染；这些规则由
AgentOrchestrator 统一控制。API key 仅从运行时环境变量读取，绝不写入日志或响应。
"""

import json
from typing import Any

import httpx

from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.models.errors import ModelProviderError


class DeepSeekProvider(ModelProvider):
    name = "deepseek"

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.deepseek.com/v1",
        model: str = "deepseek-chat",
        timeout_seconds: float = 30.0,
        temperature: float = 0.3,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._temperature = temperature
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(timeout_seconds),
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def complete(
        self, messages: list[dict], tools: list[dict] | None = None
    ) -> ModelResponse:
        if not self._api_key:
            raise ModelProviderError()

        payload: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": self._temperature,
        }
        if tools:
            payload["tools"] = _to_function_tools(tools)
            payload["tool_choice"] = "auto"

        try:
            response = await self._client.post(
                "/chat/completions",
                json=payload,
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                },
            )
            response.raise_for_status()
            body = response.json()
        except httpx.TimeoutException as exc:
            raise ModelProviderError() from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise ModelProviderError() from exc

        try:
            message = body["choices"][0]["message"]
            if not isinstance(message, dict):
                raise ValueError("message is not an object")
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ModelProviderError() from exc

        return _parse_message(message)


def _to_function_tools(tools: list[dict]) -> list[dict]:
    """把内部契约的 inputSchema 转成 OpenAI function calling 形状。"""

    result: list[dict] = []
    for definition in tools:
        if not isinstance(definition, dict):
            continue
        name = definition.get("name")
        if not isinstance(name, str) or not name:
            continue
        parameters = definition.get("inputSchema")
        if not isinstance(parameters, dict):
            parameters = {"type": "object", "properties": {}}
        result.append(
            {
                "type": "function",
                "function": {
                    "name": name,
                    "description": definition.get("description", ""),
                    "parameters": parameters,
                },
            }
        )
    return result


def _parse_message(message: dict[str, Any]) -> ModelResponse:
    calls: list[ToolCallRequest] = []
    for raw_call in message.get("tool_calls") or []:
        if not isinstance(raw_call, dict):
            continue
        function = raw_call.get("function") or {}
        if not isinstance(function, dict):
            continue
        name = function.get("name")
        if not isinstance(name, str) or not name:
            continue
        call_id = raw_call.get("id")
        if not isinstance(call_id, str):
            call_id = None
        raw_arguments = function.get("arguments", "{}")
        try:
            params = (
                json.loads(raw_arguments)
                if isinstance(raw_arguments, str)
                else raw_arguments
            )
            if not isinstance(params, dict):
                raise ValueError("arguments is not an object")
            calls.append(
                ToolCallRequest(
                    name=name,
                    params=params,
                    call_id=call_id,
                )
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            calls.append(
                ToolCallRequest(
                    name=name,
                    call_id=call_id,
                    parse_error="模型返回的工具参数不是合法 JSON 对象",
                )
            )

    return ModelResponse(
        text=message.get("content") if isinstance(message.get("content"), str) else None,
        tool_calls=calls or None,
        assistant_message=message,
    )
