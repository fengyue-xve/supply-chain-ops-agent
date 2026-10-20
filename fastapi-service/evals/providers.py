"""协议级回放：cassette = OpenAI Chat Completions 响应体序列，经真实 Provider 解析路径喂给编排层。

回放不触碰 FastAPI 生产代码：cassette 通过 httpx.MockTransport 注入 DeepSeekProvider，
与联网共用同一条解析链路（tool_calls/参数容错分支一致）。cassette 耗尽抛
CassetteExhaustedError（RuntimeError），不在 Provider 的 httpx.HTTPError/ValueError
捕获分支内，保证「模型交互序列与用例预期不符」响亮失败而非静默降级。
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx


class CassetteExhaustedError(RuntimeError):
    """回放请求超出 cassette 声明的响应轮次。"""


class Cassette:
    """按序回放的响应队列；transport() 产出注入 Provider 的 MockTransport。"""

    def __init__(self, case_id: str, responses: list[dict], source: Path | None = None) -> None:
        self.case_id = case_id
        self.source = source
        self._responses = list(responses)
        self._cursor = 0

    @property
    def total(self) -> int:
        return len(self._responses)

    @property
    def remaining(self) -> int:
        return len(self._responses) - self._cursor

    def next_response(self) -> dict:
        if self._cursor >= len(self._responses):
            raise CassetteExhaustedError(
                f"cassette 耗尽：用例 {self.case_id} 只声明 {len(self._responses)} 轮模型响应，"
                "但编排层发起了更多请求（模型交互序列与用例预期不符）"
            )
        response = self._responses[self._cursor]
        self._cursor += 1
        return response

    def transport(self) -> httpx.MockTransport:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=self.next_response())

        return httpx.MockTransport(handler)


def load_cassette(path: Path, case_id: str | None = None) -> Cassette:
    if not path.is_file():
        raise FileNotFoundError(f"cassette 文件不存在：{path}")
    with open(path, encoding="utf-8") as f:
        payload = json.load(f)
    responses = payload.get("responses") if isinstance(payload, dict) else None
    if not isinstance(responses, list) or not responses:
        raise ValueError(f"cassette 必须包含非空 responses 列表：{path}")
    for index, response in enumerate(responses, start=1):
        _validate_response(response, path, index)
    resolved_id = case_id or (payload.get("case_id") if isinstance(payload, dict) else None) or path.stem
    return Cassette(resolved_id, responses, source=path)


def _validate_response(response: object, path: Path, index: int) -> None:
    if not isinstance(response, dict):
        raise ValueError(f"cassette 第 {index} 轮响应不是对象：{path}")
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError(f"cassette 第 {index} 轮响应缺少 choices：{path}")
    first = choices[0]
    if not isinstance(first, dict) or not isinstance(first.get("message"), dict):
        raise ValueError(f"cassette 第 {index} 轮响应缺少 choices[0].message：{path}")


class CapturingTransport(httpx.AsyncBaseTransport):
    """记录每次模型请求的可见工具名与消息数；刻意不记录任何请求头（防凭据入档）。"""

    def __init__(self, inner: httpx.AsyncBaseTransport | None = None) -> None:
        self._inner = inner if inner is not None else httpx.AsyncHTTPTransport()
        self.requests: list[dict] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(await _describe_model_request(request))
        return await self._inner.handle_async_request(request)

    async def aclose(self) -> None:
        await self._inner.aclose()


async def _describe_model_request(request: httpx.Request) -> dict:
    try:
        raw = request.content
    except httpx.RequestNotRead:
        raw = await request.aread()
    try:
        body = json.loads(raw.decode("utf-8")) if raw else {}
    except (ValueError, UnicodeDecodeError):
        body = {}
    if not isinstance(body, dict):
        body = {}
    tools: list[str] = []
    for item in body.get("tools") or []:
        if isinstance(item, dict):
            function = item.get("function")
            if isinstance(function, dict) and isinstance(function.get("name"), str):
                tools.append(function["name"])
    messages = body.get("messages") or []
    return {
        "path": request.url.path,
        "tools": tools,
        "message_count": len(messages) if isinstance(messages, list) else 0,
    }
