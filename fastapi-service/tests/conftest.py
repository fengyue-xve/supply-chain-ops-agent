"""共享夹具：Java 行为全部走 httpx.MockTransport，测试不依赖真实 Java / MySQL / 外网。"""

import json
from pathlib import Path

import httpx
import pytest

from app.main import create_app
from app.models.fake import FakeModelProvider
from app.rag.retriever import EmptyRetriever
from app.tools.java_client import JavaToolClient
from app.tools.schema_loader import ToolSchemaLoader

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT_PATH = PROJECT_ROOT / "contracts" / "tools-schema.json"


def schema_payload() -> dict:
    data = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
    return {"code": 0, "message": "ok", "data": data}


@pytest.fixture
def payloads() -> dict:
    return {
        "query-stock-level": {
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
                    },
                    {
                        "sku": "CHG-001",
                        "productName": "快充头",
                        "category": "charging",
                        "availableQty": 25,
                        "stockLevel": "ok",
                        "snapDate": "2026-09-03",
                    },
                ],
                "missingSkus": [],
                "total": 2,
            },
        },
        "query-sales": {
            "code": 0,
            "message": "ok",
            "data": {
                "items": [
                    {
                        "sku": "AUD-001",
                        "productName": "无线耳机",
                        "category": "audio",
                        "days": 30,
                        "totalQty": 11,
                        "avgDailyQty": 0.37,
                        "series": [
                            {"date": "2026-08-05", "qty": 1},
                            {"date": "2026-09-03", "qty": 0},
                        ],
                    }
                ],
                "missingSkus": [],
                "total": 1,
            },
        },
        "suggest-replenish": {
            "code": 0,
            "message": "ok",
            "data": {
                "items": [
                    {
                        "sku": "ACC-001",
                        "productName": "手机壳",
                        "category": "accessory",
                        "currentQty": 5,
                        "avgDailyQty": 1.5,
                        "targetCoverDays": 14,
                        "safetyQty": 20,
                        "suggestedQty": 36,
                        "urgency": "urgent",
                        "reason": "演示理由",
                    }
                ],
                "missingSkus": [],
                "total": 1,
            },
        },
    }


def _build_client(*, tool_payloads=None, schema_ok=True, health_ok=True, connect_down=False):
    tool_payloads = tool_payloads or {}

    def handler(request: httpx.Request) -> httpx.Response:
        if connect_down:
            raise httpx.ConnectError("connection refused", request=request)
        path = request.url.path
        if path == "/health":
            return httpx.Response(
                200 if health_ok else 503,
                json={"status": "up"} if health_ok else {},
            )
        if path == "/api/agent/tools/schema":
            if not schema_ok:
                return httpx.Response(503, json={"code": 503, "message": "java down"})
            return httpx.Response(200, json=schema_payload())
        if path.startswith("/api/agent/tools/"):
            name = path.rsplit("/", 1)[1]
            payload = tool_payloads.get(name)
            if payload is None:
                return httpx.Response(404, json={"code": 404, "message": "未知工具"})
            return httpx.Response(200, json=payload)
        return httpx.Response(404, json={"code": 404, "message": "not found"})

    return JavaToolClient(
        base_url="http://java.test", transport=httpx.MockTransport(handler)
    )


@pytest.fixture
def java_client_factory():
    return _build_client


@pytest.fixture
def app_env(java_client_factory):
    def _build(*, tool_payloads=None, schema_ok=True, health_ok=True, connect_down=False):
        merged = tool_payloads
        client = java_client_factory(
            tool_payloads=merged,
            schema_ok=schema_ok,
            health_ok=health_ok,
            connect_down=connect_down,
        )
        loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
        app = create_app(
            java_client=client,
            provider=FakeModelProvider(),
            schema_loader=loader,
            retriever=EmptyRetriever(),
        )
        return app, client, loader

    return _build
