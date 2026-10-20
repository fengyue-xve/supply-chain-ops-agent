import asyncio

from app.models.base import ModelResponse, ToolCallRequest
from app.models.fake import ASK_SKU_OR_CATEGORY, UNKNOWN_REPLY, FakeModelProvider


def complete(message: str) -> ModelResponse:
    return asyncio.run(FakeModelProvider().complete([{"role": "user", "content": message}]))


def _single(response: ModelResponse) -> ToolCallRequest:
    assert response.tool_calls is not None and len(response.tool_calls) == 1
    return response.tool_calls[0]


def test_stock_intent_with_sku():
    call = _single(complete("AUD-001 库存还有多少？"))
    assert call.name == "query-stock-level"
    assert call.params == {"skus": ["AUD-001"]}


def test_stock_intent_with_category():
    call = _single(complete("充电类目现存量怎么样"))
    assert call.name == "query-stock-level"
    assert call.params == {"category": "charging"}


def test_sales_intent_with_sku_and_days():
    call = _single(complete("AUD-001 近30天卖得怎么样"))
    assert call.name == "query-sales"
    assert call.params == {"sku": "AUD-001", "days": 30}


def test_sales_intent_default_days_seven():
    call = _single(complete("音频类目每天卖多少"))
    assert call.name == "query-sales"
    assert call.params == {"category": "audio", "days": 7}


def test_replenish_intent_with_sku():
    call = _single(complete("ACC-001 要不要补货？"))
    assert call.name == "suggest-replenish"
    assert call.params == {"skus": ["ACC-001"], "days": 7}


def test_multiple_skus_collected():
    call = _single(complete("AUD-001、CHG-001 库存够不够"))
    assert call.params == {"skus": ["AUD-001", "CHG-001"]}


def test_intent_without_params_asks_back():
    response = complete("库存怎么样？")
    assert response.tool_calls is None
    assert response.text == ASK_SKU_OR_CATEGORY


def test_unknown_intent_returns_plain_text():
    response = complete("今天天气怎么样")
    assert response.tool_calls is None
    assert response.text == UNKNOWN_REPLY


def test_empty_message_returns_plain_text():
    response = complete("")
    assert response.tool_calls is None
    assert response.text == UNKNOWN_REPLY
