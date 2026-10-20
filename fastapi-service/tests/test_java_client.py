import asyncio
import json

import httpx

from app.tools.java_client import JavaToolClient


def _client(handler) -> JavaToolClient:
    return JavaToolClient(
        base_url="http://java.test", transport=httpx.MockTransport(handler)
    )


def run(coro):
    return asyncio.run(coro)


def test_call_ok_unwraps_envelope():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": 0, "message": "ok", "data": {"total": 1}})

    outcome = run(_client(handler).call("query-stock-level", {"skus": ["AUD-001"]}))
    assert outcome.ok is True
    assert outcome.data == {"total": 1}
    assert outcome.error is None


def test_call_business_error_keeps_java_message():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": 400, "message": "skus 与 category 只能二选一"})

    outcome = run(_client(handler).call("query-sales", {"sku": "A", "category": "audio"}))
    assert outcome.ok is False
    assert outcome.error == "skus 与 category 只能二选一"
    assert outcome.http_code == 200


def test_call_http_error_surfaces_envelope():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"code": 503, "message": "数据库访问异常"})

    outcome = run(_client(handler).call("query-stock-level", {}))
    assert outcome.ok is False
    assert outcome.error == "数据库访问异常"
    assert outcome.http_code == 503


def test_call_non_json_body():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, content=b"oops")

    outcome = run(_client(handler).call("query-stock-level", {}))
    assert outcome.ok is False
    assert "非 JSON" in outcome.error


def test_call_timeout_is_structured():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timed out", request=request)

    outcome = run(_client(handler).call("query-stock-level", {}))
    assert outcome.ok is False
    assert "超时" in outcome.error


def test_call_connection_error_is_structured():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    outcome = run(_client(handler).call("query-stock-level", {}))
    assert outcome.ok is False
    assert "不可用" in outcome.error


def test_ping_true_on_200():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "up"})

    assert run(_client(handler).ping()) is True


def test_ping_false_on_down_or_error():
    assert run(_client(lambda r: httpx.Response(503, json={})).ping()) is False

    def raising(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    assert run(_client(raising).ping()) is False


def test_fetch_schema_ok():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": 0, "message": "ok", "data": {"version": "1"}})

    schema = run(_client(handler).fetch_schema())
    assert schema["version"] == "1"


def test_fetch_schema_raises_on_java_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"code": 503, "message": "down"})

    try:
        run(_client(handler).fetch_schema())
    except RuntimeError as exc:
        assert "HTTP 503" in str(exc)
    else:
        raise AssertionError("应当抛出 RuntimeError")


def test_call_posts_tool_name_and_params():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        captured["body"] = json.loads(request.content)
        captured["request_id"] = request.headers.get("x-request-id")
        return httpx.Response(200, json={"code": 0, "message": "ok", "data": {}})

    outcome = run(
        _client(handler).call(
            "query-stock-level", {"skus": ["AUD-001"]}, request_id="req-test"
        )
    )
    assert outcome.ok is True
    assert captured == {
        "path": "/api/agent/tools/query-stock-level",
        "body": {"skus": ["AUD-001"]},
        "request_id": "req-test",
    }
