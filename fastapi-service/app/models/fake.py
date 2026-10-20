"""FakeModelProvider：规则优先意图表（无 IO 纯函数，可单测）。

命中三意图 → 结构化工具调用；命中但缺 SKU/类目 → 追问文本（不硬调出必 400 的调用）；
未知意图 → 兜底文本。未来真实 LLM 以同接口替换，编排器不感知。
"""

import re

from app.models.base import ModelProvider, ModelResponse, ToolCallRequest

SKU_RE = re.compile(r"[A-Z]{3}-\d{3}")
_CATEGORY_WORDS = {
    "audio": ("音频", "耳机", "音箱", "音响"),
    "charging": ("充电",),
    "accessory": ("配件",),
}
_TOOL_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("query-stock-level", ("库存", "还有多少", "现存量", "够不够", "剩多少")),
    ("query-sales", ("销量", "卖得", "每天卖", "卖了多少", "近7天", "近30天", "近 7 天", "近 30 天")),
    ("suggest-replenish", ("补货", "进货", "备货")),
]

ASK_SKU_OR_CATEGORY = "请告诉我是哪个 SKU（如 AUD-001）或类目（音频/充电/配件），我再帮你查。"
UNKNOWN_REPLY = "我目前只会查库存、销量和补货建议，请换个问法。"


def match_plan(text: str) -> ToolCallRequest | None:
    """返回工具调用计划；无法识别意图或参数不齐时返回 None（调用方决定回复文本）。"""
    skus = SKU_RE.findall(text)
    category = _find_category(text)
    for tool, keywords in _TOOL_KEYWORDS:
        if not any(word in text for word in keywords):
            continue
        if not skus and category is None:
            return None
        if tool == "query-sales":
            params: dict = {"days": _detect_days(text)}
            if skus:
                params["sku"] = skus[0]
            else:
                params["category"] = category
        elif tool == "suggest-replenish":
            params = {"days": _detect_days(text)}
            if skus:
                params["skus"] = skus
            else:
                params["category"] = category
        else:
            params = {"skus": skus} if skus else {"category": category}
        return ToolCallRequest(name=tool, params=params)
    return None


def _find_category(text: str) -> str | None:
    for category, words in _CATEGORY_WORDS.items():
        if any(word in text for word in words):
            return category
    return None


def _detect_days(text: str) -> int:
    if re.search(r"近\s*30|30\s*天|30日|一个月", text):
        return 30
    if re.search(r"近\s*7|7\s*天|7日|一周", text):
        return 7
    return 7


class FakeModelProvider(ModelProvider):
    name = "fake-rule"

    async def complete(self, messages, tools=None) -> ModelResponse:
        user_contents = [m.get("content", "") for m in messages if m.get("role") == "user"]
        text = user_contents[-1] if user_contents else ""
        plan = match_plan(text)
        if plan is None:
            reply = ASK_SKU_OR_CATEGORY if _has_intent_word(text) else UNKNOWN_REPLY
            return ModelResponse(text=reply)
        return ModelResponse(tool_calls=[plan])


def _has_intent_word(text: str) -> bool:
    return any(word in text for _, keywords in _TOOL_KEYWORDS for word in keywords)
