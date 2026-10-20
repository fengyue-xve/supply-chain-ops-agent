import asyncio

import httpx

from app.tools.java_client import JavaToolClient
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload


def _client(handler) -> JavaToolClient:
    return JavaToolClient(
        base_url="http://java.test", transport=httpx.MockTransport(handler)
    )


def _loader(client, ttl=600.0) -> ToolSchemaLoader:
    return ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH, ttl_seconds=ttl)


def test_prefers_java_when_reachable():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=schema_payload())

    loader = _loader(_client(handler))
    schema = asyncio.run(loader.load())
    assert loader.source == "java"
    assert schema["version"] == "1"
    assert len(schema["tools"]) == 5


def test_falls_back_to_snapshot_when_java_down():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    loader = _loader(_client(handler))
    schema = asyncio.run(loader.load())
    assert loader.source == "snapshot"
    assert schema["version"] == "1"
    assert len(schema["tools"]) == 5


def test_serves_from_cache_without_refetch():
    calls = {"schema": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/agent/tools/schema":
            calls["schema"] += 1
            return httpx.Response(200, json=schema_payload())
        raise httpx.ConnectError("refused", request=request)

    loader = _loader(_client(handler))
    first = asyncio.run(loader.load())
    second = asyncio.run(loader.load())
    assert first == second
    assert calls["schema"] == 1


def test_recovers_to_java_after_snapshot_fallback():
    down = {"phase": "down"}

    def handler(request: httpx.Request) -> httpx.Response:
        if down["phase"] == "down":
            raise httpx.ConnectError("refused", request=request)
        return httpx.Response(200, json=schema_payload())

    loader = _loader(_client(handler))
    asyncio.run(loader.load())
    assert loader.source == "snapshot"

    down["phase"] = "up"
    loader.invalidate()
    asyncio.run(loader.load())
    assert loader.source == "java"
