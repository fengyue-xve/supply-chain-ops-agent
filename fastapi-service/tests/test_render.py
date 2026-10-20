from app.agent.render import render_tool_result


def test_stock_render_includes_key_fields():
    text = render_tool_result(
        "query-stock-level",
        {
            "items": [
                {
                    "sku": "AUD-001",
                    "productName": "无线耳机",
                    "availableQty": 18,
                    "stockLevel": "low",
                    "snapDate": "2026-09-03",
                }
            ],
            "missingSkus": [],
            "total": 1,
        },
    )
    assert "AUD-001" in text
    assert "无线耳机" in text
    assert "18" in text
    assert "low" in text
    assert "2026-09-03" in text


def test_sales_render_shows_window_edge_dates():
    text = render_tool_result(
        "query-sales",
        {
            "items": [
                {
                    "sku": "AUD-001",
                    "productName": "无线耳机",
                    "days": 30,
                    "totalQty": 11,
                    "avgDailyQty": 0.37,
                    "series": [
                        {"date": "2026-08-05", "qty": 1},
                        {"date": "2026-08-06", "qty": 0},
                        {"date": "2026-09-03", "qty": 0},
                    ],
                }
            ],
            "missingSkus": [],
            "total": 1,
        },
    )
    assert "11" in text
    assert "2026-08-05 ~ 2026-09-03" in text
    assert "0.37" in text


def test_replenish_render_includes_urgency():
    text = render_tool_result(
        "suggest-replenish",
        {
            "items": [
                {
                    "sku": "ACC-001",
                    "productName": "手机壳",
                    "currentQty": 5,
                    "avgDailyQty": 1.5,
                    "suggestedQty": 36,
                    "urgency": "urgent",
                }
            ],
            "missingSkus": [],
            "total": 1,
        },
    )
    assert "建议补货 36" in text
    assert "urgent" in text


def test_missing_skus_are_stated_not_dropped():
    text = render_tool_result(
        "query-stock-level", {"items": [], "missingSkus": ["XXX-001"], "total": 0}
    )
    assert "XXX-001" in text


def test_empty_items_yields_honest_fallback():
    text = render_tool_result("query-stock-level", {"items": [], "missingSkus": [], "total": 0})
    assert text == "该范围暂无数据。"


def test_none_data_is_honest():
    assert "空结果" in render_tool_result("query-stock-level", None)
