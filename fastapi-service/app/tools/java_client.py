"""Java 工具客户端：只与 Java HTTP 接口对话，不触数据库。

错误哲学：连接失败 / 超时 / Java 信封错误全部结构化进 ToolCallOutcome，不向编排器抛异常
（局部失败可见可审计，与 Java 侧 missingSkus「不整单失败」同构）。
"""

from dataclasses import dataclass
from typing import Any

import httpx

_DEFAULT_SCHEMA_TIMEOUT = 2.0


def _headers(request_id: str | None, authorization: str | None) -> dict[str, str] | None:
    """组装透传头：X-Request-Id 与用户 Authorization 原样转发，不落日志不落缓存。"""
    headers = {}
    if request_id:
        headers["X-Request-Id"] = request_id
    if authorization:
        headers["Authorization"] = authorization
    return headers or None


@dataclass(frozen=True)
class ToolCallOutcome:
    ok: bool
    data: dict[str, Any] | None = None
    error: str | None = None
    http_code: int | None = None


class JavaToolClient:
    def __init__(
        self,
        base_url: str,
        timeout_seconds: float = 3.0,
        health_timeout_seconds: float = 1.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=httpx.Timeout(timeout_seconds),
            transport=transport,
            # Internal service calls carry user credentials; bypass workstation proxies.
            trust_env=False,
        )
        self._health_timeout = httpx.Timeout(health_timeout_seconds)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def call(
        self,
        name: str,
        params: dict[str, Any],
        request_id: str | None = None,
        authorization: str | None = None,
    ) -> ToolCallOutcome:
        try:
            headers = _headers(request_id, authorization)
            response = await self._client.post(
                f"/api/agent/tools/{name}", json=params, headers=headers
            )
        except httpx.TimeoutException:
            return ToolCallOutcome(ok=False, error="工具调用超时，请稍后重试")
        except httpx.HTTPError:
            return ToolCallOutcome(ok=False, error="工具服务暂不可用，请稍后重试")
        return self._to_outcome(response)

    async def fetch_schema(
        self, authorization: str | None = None
    ) -> dict[str, Any]:
        response = await self._client.get(
            "/api/agent/tools/schema",
            timeout=httpx.Timeout(_DEFAULT_SCHEMA_TIMEOUT),
            headers=_headers(None, authorization),
        )
        if response.status_code != 200:
            raise RuntimeError(f"schema 获取失败：HTTP {response.status_code}")
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(body.get("message") or "schema 获取失败")
        return body["data"]

    async def ping(self) -> bool:
        try:
            response = await self._client.get("/health", timeout=self._health_timeout)
            return response.status_code == 200
        except httpx.HTTPError:
            return False

    @staticmethod
    def _to_outcome(response: httpx.Response) -> ToolCallOutcome:
        try:
            body = response.json()
        except ValueError:
            return ToolCallOutcome(
                ok=False,
                error=f"工具返回非 JSON 响应（HTTP {response.status_code}）",
                http_code=response.status_code,
            )
        if response.status_code == 200 and body.get("code") == 0:
            return ToolCallOutcome(ok=True, data=body.get("data"), http_code=200)
        message = body.get("message") or f"工具执行失败（code={body.get('code')}）"
        return ToolCallOutcome(ok=False, error=message, http_code=response.status_code)
