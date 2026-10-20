from fastapi.testclient import TestClient

from app.models.fake import ASK_SKU_OR_CATEGORY, UNKNOWN_REPLY

# 测试默认携带的用户凭证(W2-D3:HTTP 入口要求 Authorization,Java 才是最终权限边界)。
DEFAULT_AUTH = "Bearer unit-viewer-token-123"


def _post(app, payload, headers=None, auth=DEFAULT_AUTH):
    headers = dict(headers or {})
    if auth is not None:
        headers["Authorization"] = auth
    with TestClient(app) as client:
        return client.post("/agent/chat", json=payload, headers=headers)


def test_stock_intent_end_to_end(app_env, payloads):
    app, _, _ = app_env(tool_payloads=payloads)
    response = _post(
        app,
        {"message": "AUD-001 库存还有多少？"},
        headers={"X-Request-Id": "req-test00000000"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    data = body["data"]
    assert data["request_id"] == "req-test00000000"
    assert response.headers["x-request-id"] == "req-test00000000"
    assert data["conversation_id"].startswith("conv-")

    calls = data["tool_calls"]
    assert len(calls) == 1
    call = calls[0]
    assert call["tool_call_id"].startswith("tc-")
    assert call["tool"] == "query-stock-level"
    assert call["params"] == {"skus": ["AUD-001"]}
    assert call["ok"] is True
    assert "AUD-001" in call["summary"]
    assert data["reply"] == call["summary"]


def test_sales_intent_with_days(app_env, payloads):
    app, _, _ = app_env(tool_payloads=payloads)
    body = _post(app, {"message": "AUD-001 近30天销量怎么样"}).json()
    call = body["data"]["tool_calls"][0]
    assert call["tool"] == "query-sales"
    assert call["params"] == {"sku": "AUD-001", "days": 30}


def test_replenish_intent(app_env, payloads):
    app, _, _ = app_env(tool_payloads=payloads)
    body = _post(app, {"message": "ACC-001 要不要补货？"}).json()
    call = body["data"]["tool_calls"][0]
    assert call["tool"] == "suggest-replenish"
    assert call["params"] == {"skus": ["ACC-001"], "days": 7}
    assert "建议补货 36" in call["summary"]


def test_conversation_id_passthrough(app_env, payloads):
    app, _, _ = app_env(tool_payloads=payloads)
    body = _post(app, {"conversation_id": "conv-known1234", "message": "AUD-001 库存？"}).json()
    assert body["data"]["conversation_id"] == "conv-known1234"


def test_unknown_intent_is_plain_text_without_tool_calls(app_env):
    app, _, _ = app_env()
    body = _post(app, {"message": "今天天气怎么样"}).json()
    assert body["code"] == 0
    assert body["data"]["reply"] == UNKNOWN_REPLY
    assert body["data"]["tool_calls"] == []


def test_intent_without_params_asks_back(app_env):
    app, _, _ = app_env()
    body = _post(app, {"message": "库存怎么样？"}).json()
    assert body["data"]["reply"] == ASK_SKU_OR_CATEGORY
    assert body["data"]["tool_calls"] == []


def test_java_business_error_keeps_chat_ok(app_env):
    failing = {"query-stock-level": {"code": 400, "message": "skus 与 category 只能二选一"}}
    app, _, _ = app_env(tool_payloads=failing)
    response = _post(app, {"message": "AUD-001 库存多少？"})
    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 0
    call = body["data"]["tool_calls"][0]
    assert call["ok"] is False
    assert call["error"] == "skus 与 category 只能二选一"
    assert "skus 与 category 只能二选一" in body["data"]["reply"]


def test_java_fully_down_keeps_chat_ok_with_error(app_env):
    app, _, _ = app_env(connect_down=True)
    response = _post(app, {"message": "AUD-001 库存多少？"})
    assert response.status_code == 200
    body = response.json()
    call = body["data"]["tool_calls"][0]
    assert call["ok"] is False
    assert "不可用" in call["error"]


def test_empty_message_returns_400_envelope(app_env):
    app, _, _ = app_env()
    for payload in ({"message": ""}, {"message": "   "}):
        response = _post(app, payload)
        assert response.status_code == 400
        body = response.json()
        assert body["code"] == 400
        assert body["message"] == "message 不能为空"


def test_missing_message_field_returns_400_envelope(app_env):
    app, _, _ = app_env()
    response = _post(app, {})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == 400
    assert body["message"] == "请求体格式错误"


def test_oversized_message_returns_400_envelope(app_env):
    app, _, _ = app_env()
    response = _post(app, {"message": "x" * 2001})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == 400
    assert body["message"] == "请求体格式错误"
