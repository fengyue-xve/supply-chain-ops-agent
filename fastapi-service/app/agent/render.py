"""工具结果 data → 中文文本（纯函数，只转述结构、不编造、不解释）。

三种 data 均形如 {items, missingSkus, total}；series 逐日太长，只给统计区间首尾日期。
"""


def render_tool_result(tool: str, data: dict | None) -> str:
    if not isinstance(data, dict):
        return "工具返回了空结果。"
    items = data.get("items") or []
    missing = data.get("missingSkus") or []
    lines = [_render_item(tool, item) for item in items if isinstance(item, dict)]
    if missing:
        lines.append("未找到这些 SKU 的记录：" + "、".join(missing))
    if lines:
        return "\n".join(lines)
    return "该范围暂无数据。" if not missing else "没有查到相关数据。"


def _render_item(tool: str, item: dict) -> str:
    sku = item.get("sku", "?")
    name = item.get("productName", "")
    if tool == "query-stock-level":
        return (
            f"{sku}（{name}）当前库存 {item.get('availableQty')}，"
            f"档位 {item.get('stockLevel')}，快照 {item.get('snapDate')}"
        )
    if tool == "query-sales":
        series = item.get("series") or []
        window = ""
        if series and isinstance(series[0], dict) and isinstance(series[-1], dict):
            window = f"，统计 {series[0].get('date')} ~ {series[-1].get('date')}"
        return (
            f"{sku}（{name}）近 {item.get('days')} 天总销量 {item.get('totalQty')}，"
            f"日均 {item.get('avgDailyQty')}{window}"
        )
    if tool == "suggest-replenish":
        status = item.get("assessmentStatus")
        if status == "insufficient_data" or item.get("suggestedQty") is None:
            return f"{sku}（{name}）数据不足，暂不提供补货数量；{item.get('reason') or '请核对销量与商品策略。'}"
        text = (
            f"{sku}（{name}）建议补货 {item.get('suggestedQty')} 件"
            f"（当前 {item.get('currentQty')}，日均 {item.get('avgDailyQty')}，"
            f"紧急度 {item.get('urgency')}）"
        )
        if status:
            dates = f"；库存快照 {item.get('stockSnapDate') or '未知'}，销量窗口 {item.get('salesWindowStart') or '未知'} ~ {item.get('salesWindowEnd') or '未知'}"
            scope = "；仅现存库存口径，不计预留与在途，采购意向待人工复核"
            warning = "；需人工复核" if status != "ready" else ""
            return text + dates + scope + warning + f"；{item.get('reason') or ''}"
        return text
    return f"{sku}（{name}）"
