"""W2-D3：/agent/chat 用户凭证透传边界（FastAPI 不做权限判定，Java 才是最终边界）。

覆盖：Authorization 原样透传给 schema/工具请求；缺 Authorization → 结构化 401 且不匿名
调用 Java；Java 401/403 → tool_call 保留失败结构不伪造成模型成功；schema 缓存不含 token；
缓存命中不因 token 不同而分裂。
"""

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models.fake import FakeModelProvider
from app.rag.retriever import EmptyRetriever
from app.tools.java_client import JavaToolClient
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload
from tests.test_chat import DEFAULT_AUTH

STOCK_PAYLOAD = {
    "code": 0,
    "message": "ok",
    "data": {
        "items": [
            {
                "sku": "AUD-001",
                "productName": "无线耳机",
                "category": "audio",
                "availableQty": 18,
                "stockLevel": "low",
                "snapDate": "2026-09-03",
            }
        ],
        "missingSkus": [],
        "total": 1,
    },
}


def _build_app(seen, calls, *, tool_payloads=None):
    """Java 行为走 MockTransport，捕获每个出站请求的 Authorization/X-Request-Id。"""
    tool_payloads = tool_payloads or {}

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/agent/tools/schema":
            calls["schema"] += 1
            seen.append(
                {
                    "kind": "schema",
                    "auth": request.headers.get("authorization"),
                    "rid": request.headers.get("x-request-id"),
                }
            )
            return httpx.Response(200, json=schema_payload())
        if path.startswith("/api/agent/tools/"):
            name = path.rsplit("/", 1)[1]
            seen.append(
                {
                    "kind": "tool",
                    "tool": name,
                    "auth": request.headers.get("authorization"),
                    "rid": request.headers.get("x-request-id"),
                }
            )
            payload = tool_payloads.get(name, STOCK_PAYLOAD)
            return httpx.Response(200, json=payload)
        raise httpx.ConnectError("refused", request=request)

    client = JavaToolClient(
        base_url="http://java.test", transport=httpx.MockTransport(handler)
    )
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    app = create_app(
        java_client=client,
        provider=FakeModelProvider(),
        schema_loader=loader,
        retriever=EmptyRetriever(),
    )
    return app, loader, calls


def _post(app, payload, headers=None, auth=DEFAULT_AUTH):
    headers = dict(headers or {})
    if auth is not None:
        headers["Authorization"] = auth
    with TestClient(app) as client:
        return client.post("/agent/chat", json=payload, headers=headers)


def test_authorization_is_passed_to_schema_and_tool_requests():
    seen = []
    calls = {"schema": 0}
    app, _, _ = _build_app(seen, calls)
    response = _post(app, {"message": "AUD-001 库存还有多少？"})

    assert response.status_code == 200
    kinds = [entry["kind"] for entry in seen]
    assert "schema" in kinds and "tool" in kinds
    for entry in seen:
        assert entry["auth"] == DEFAULT_AUTH


def test_missing_authorization_returns_401_without_touching_java():
    seen = []
    calls = {"schema": 0}
    app, _, _ = _build_app(seen, calls)
    response = _post(app, {"message": "AUD-001 库存还有多少？"}, auth=None)

    assert response.status_code == 401
    body = response.json()
    assert body["code"] == 401
    assert "Authorization" in body["message"]
    assert seen == []
    assert calls["schema"] == 0


def test_java_401_keeps_tool_call_failure_structure():
    failing = {"query-stock-level": {"code": 401, "message": "token 无效"}}
    app, _, _ = _build_app([], {"schema": 0}, tool_payloads=failing)
    response = _post(app, {"message": "AUD-001 库存还有多少？"})

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    call = body["data"]["tool_calls"][0]
    assert call["ok"] is False
    assert call["error"] == "token 无效"


def test_java_403_keeps_tool_call_denied_semantics():
    failing = {"query-stock-level": {"code": 403, "message": "无权限执行该操作"}}
    app, _, _ = _build_app([], {"schema": 0}, tool_payloads=failing)
    response = _post(app, {"message": "AUD-001 库存还有多少？"})

    assert response.status_code == 200
    call = response.json()["data"]["tool_calls"][0]
    assert call["ok"] is False
    assert call["error"] == "无权限执行该操作"


def test_schema_cache_holds_no_token_and_does_not_split_by_authorization():
    seen = []
    calls = {"schema": 0}
    app, loader, _ = _build_app(seen, calls)

    with TestClient(app) as client:  # lifespan 只进一次,两次请求共享同一 Java client
        first = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？"},
            headers={"Authorization": "Bearer token-aaaa"},
        )
        assert first.status_code == 200
        second = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？"},
            headers={"Authorization": "Bearer token-bbbb"},
        )
        assert second.status_code == 200

    assert calls["schema"] == 1  # 缓存命中，未因 token 不同而重新拉取
    cached_schema = loader._cached
    assert isinstance(cached_schema, dict)
    payload = repr(cached_schema)
    assert "token-aaaa" not in payload and "token-bbbb" not in payload
    assert "authorization" not in {str(k).lower() for k in cached_schema.keys()}
    schema_entries = [entry for entry in seen if entry["kind"] == "schema"]
    assert len(schema_entries) == 1
    assert schema_entries[0]["auth"] == "Bearer token-aaaa"


def test_chat_response_and_records_never_carry_authorization():
    seen = []
    app, _, _ = _build_app(seen, {"schema": 0})
    response = _post(app, {"message": "AUD-001 库存？"})

    payload = response.text
    assert DEFAULT_AUTH not in payload
    assert "Bearer" not in payload
