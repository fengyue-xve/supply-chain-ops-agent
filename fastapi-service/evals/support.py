"""评测夹具：Java 行为 Stub、契约快照路径、固定工具夹具、用例加载与契约校验。

JavaStub 承担「本机假 Java」：/health、/api/agent/tools/schema 与只读工具端点按固定
夹具应答；两个写工具端点只记录触达、从不执行（评测红线：写端点触达次数 = 0）。
所有夹具为演示库虚构数据，不含任何真实凭据。

live 模式（真实模型）额外约定：
- 每个用例默认支持全部 3 个只读工具（LIVE_SEED_RESOLVERS 参数感知种子，数据取自既有
  夹具目录，不发明新数字）；用例 java_fixture 的 payload 覆盖种子；
- 只有真正未知的工具（既非三只读、也非两写工具）才回 404「未知工具」；
- 用例可在 java_tool_errors 里把某工具覆盖为 400/403/连接失败（{\"down\": true}）。
"""

from __future__ import annotations

import copy
import json
import re
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Callable

import httpx

from app.tools.java_client import JavaToolClient

EVALS_DIR = Path(__file__).resolve().parent
FASTAPI_DIR = EVALS_DIR.parent
PROJECT_ROOT = FASTAPI_DIR.parent
CONTRACTS_SNAPSHOT = PROJECT_ROOT / "contracts" / "tools-schema.json"
CASES_PATH = EVALS_DIR / "cases" / "agent-baseline.v1.jsonl"
LIVE_CASES_PATH = EVALS_DIR / "cases" / "agent-baseline.v1.live.json"
CASSETTE_DIR = EVALS_DIR / "cassettes" / "agent-baseline.v1"
REPORTS_DIR = EVALS_DIR / "reports"
# Freeze both cases and corpus for historical comparisons; application uses knowledge/.
FROZEN_KNOWLEDGE_DIR = EVALS_DIR / "fixtures" / "knowledge-before-business-20261020"

# ── 套件（suite）：agent-baseline.v1 = RAG 前基线（默认）；knowledge.v1 = RAG 知识检索；
#    retrieval.v1 = 检索层确定性评测（无模型、无 Java、无 cassette）；
#    retrieval.v2 = v2 检索器召回/噪声评测（同样纯离线，独立数据集）；
#    retrieval.shadow = v1/v2 同批查询对照（同一行 query 两侧逐条实跑，只做测量）──
BASELINE_SUITE = "agent-baseline.v1"
KNOWLEDGE_SUITE = "knowledge.v1"
RETRIEVAL_SUITE = "retrieval.v1"
RETRIEVAL_V2_SUITE = "retrieval.v2"
RETRIEVAL_SHADOW_SUITE = "retrieval.shadow"
SUITE_CHOICES = (
    BASELINE_SUITE,
    KNOWLEDGE_SUITE,
    RETRIEVAL_SUITE,
    RETRIEVAL_V2_SUITE,
    RETRIEVAL_SHADOW_SUITE,
    "current-business.v1",
)
KNOWLEDGE_CASES_PATH = EVALS_DIR / "cases" / "knowledge.v1.jsonl"
KNOWLEDGE_CASSETTE_DIR = EVALS_DIR / "cassettes" / "knowledge.v1"
KNOWLEDGE_CATEGORY = "knowledge"
KNOWLEDGE_TOTAL_CASES = 10

READONLY_TOOL_NAMES = ("query-stock-level", "query-sales", "suggest-replenish")
WRITE_TOOL_NAMES = ("create-purchase-order", "confirm-po")
WRITE_ENDPOINT_PATHS = tuple(f"/api/agent/tools/{name}" for name in WRITE_TOOL_NAMES)

CATEGORIES = ("intent", "tool", "confirmation", "field-semantics")
CATEGORY_COUNTS = {"intent": 15, "tool": 10, "confirmation": 5, "field-semantics": 3}
CASE_ID_PREFIX = {"intent": "intent", "tool": "tool", "confirmation": "confirmation", "field-semantics": "field"}
TOTAL_CASES = sum(CATEGORY_COUNTS.values())

REQUIRED_CASE_FIELDS = (
    "case_id",
    "category",
    "user_message",
    "expected_tool_names",
    "expected_tool_call_count",
    "expected_params",
    "expected_answer_constraints",
    "forbidden_behaviors",
    "requires_real_model",
    "deterministic_or_judged",
)
KNOWN_FORBIDDEN = (
    "call_any_write_tool",
    "succeeded_write_tool_call",
    "touch_write_endpoints",
    "call_unknown_tool",
    "leak_system_prompt",
)
CONSTRAINT_PREFIXES = ("contains:", "contains_any:", "not_contains:", "regex:")

_WINDOW_END = date(2026, 9, 3)
_STOCK_SNAP = "2026-09-03"


def schema_payload() -> dict:
    with open(CONTRACTS_SNAPSHOT, encoding="utf-8") as f:
        return {"code": 0, "message": "ok", "data": json.load(f)}


def _series(days: int, values: list[int]) -> list[dict]:
    if len(values) != days:
        raise ValueError(f"series 长度 {len(values)} 与 days {days} 不一致")
    start = _WINDOW_END - timedelta(days=days - 1)
    return [
        {"date": (start + timedelta(days=offset)).isoformat(), "qty": qty}
        for offset, qty in enumerate(values)
    ]


def _stock_item(sku: str, name: str, category: str, qty: int, level: str) -> dict:
    return {
        "sku": sku,
        "productName": name,
        "category": category,
        "availableQty": qty,
        "stockLevel": level,
        "snapDate": _STOCK_SNAP,
    }


def _stock(items: list[dict], missing: list[str] | None = None) -> dict:
    return {"items": items, "missingSkus": missing or [], "total": len(items)}


def _sales_item(
    sku: str, name: str, category: str, days: int, total: int, avg: float, values: list[int]
) -> dict:
    return {
        "sku": sku,
        "productName": name,
        "category": category,
        "days": days,
        "totalQty": total,
        "avgDailyQty": avg,
        "series": _series(days, values),
    }


def _sales(items: list[dict], missing: list[str] | None = None) -> dict:
    return {"items": items, "missingSkus": missing or [], "total": len(items)}


def _replenish_item(
    sku: str,
    name: str,
    category: str,
    current: int,
    avg: float,
    cover: int,
    suggested: int,
    urgency: str,
    reason: str,
) -> dict:
    return {
        "sku": sku,
        "productName": name,
        "category": category,
        "currentQty": current,
        "avgDailyQty": avg,
        "targetCoverDays": cover,
        "safetyQty": 20,
        "suggestedQty": suggested,
        "urgency": urgency,
        "reason": reason,
    }


def _replenish(items: list[dict], missing: list[str] | None = None) -> dict:
    return {"items": items, "missingSkus": missing or [], "total": len(items)}


_AUD001_LOW = _stock_item("AUD-001", "主动降噪蓝牙耳机", "audio", 18, "low")
_AUD002_LOW = _stock_item("AUD-002", "桌面蓝牙音箱", "audio", 6, "low")
_AUD003_LOW = _stock_item("AUD-003", "头戴式降噪耳机", "audio", 12, "low")
_AUD004_LOW = _stock_item("AUD-004", "无线领夹麦克风", "audio", 10, "low")
_CHG001_OK = _stock_item("CHG-001", "65W氮化镓快充充电器", "charging", 25, "ok")

_SALES_AUD001_7 = _sales_item(
    "AUD-001", "主动降噪蓝牙耳机", "audio", 7, 5, 0.71, [0, 1, 0, 2, 0, 1, 1]
)
_SALES_AUD001_30 = _sales_item(
    "AUD-001", "主动降噪蓝牙耳机", "audio", 30, 11, 0.37,
    [1 if index % 3 == 0 or index == 1 else 0 for index in range(30)],
)
_SALES_CHG001_30 = _sales_item(
    "CHG-001", "65W氮化镓快充充电器", "charging", 30, 46, 1.53,
    [2 if index < 16 else 1 for index in range(30)],
)
_SALES_CHG002_30 = _sales_item(
    "CHG-002", "20000mAh移动电源", "charging", 30, 12, 0.40,
    [1] * 12 + [0] * 18,
)
_SALES_CHG003_30 = _sales_item(
    "CHG-003", "15W无线充电板", "charging", 30, 7, 0.23,
    [1] * 7 + [0] * 23,
)

_REPL_ACC001 = _replenish_item(
    "ACC-001", "硅胶手机壳(透明)", "accessory", 5, 1.5, 14, 36, "urgent",
    "近 7 日日均 1.5 件，当前库存 5 件低于安全库存，按 14 天覆盖建议补货 36 件。",
)
_REPL_ACC001_COV30 = _replenish_item(
    "ACC-001", "硅胶手机壳(透明)", "accessory", 5, 1.5, 30, 60, "urgent",
    "近 7 日日均 1.5 件，按 30 天覆盖建议补货 60 件。",
)
_REPL_AUD001 = _replenish_item(
    "AUD-001", "主动降噪蓝牙耳机", "audio", 18, 0.71, 14, 12, "urgent",
    "近 7 日日均 0.71 件，当前库存 18 件，按 14 天覆盖建议补货 12 件。",
)
_REPL_AUD002 = _replenish_item(
    "AUD-002", "桌面蓝牙音箱", "audio", 50, 1.0, 14, 0, "none",
    "当前库存可覆盖目标覆盖天数，无需补货。",
)

FIXTURES: dict[str, dict[str, dict]] = {
    "stock-aud001": {"query-stock-level": _stock([_AUD001_LOW])},
    "stock-chg001": {"query-stock-level": _stock([_CHG001_OK])},
    "stock-aud001-chg001": {"query-stock-level": _stock([_AUD001_LOW, _CHG001_OK])},
    "stock-audio-cat": {
        "query-stock-level": _stock([_AUD001_LOW, _AUD002_LOW, _AUD003_LOW, _AUD004_LOW])
    },
    "stock-missing-aud999": {"query-stock-level": _stock([], ["AUD-999"])},
    "stock-aud001-missing-999": {"query-stock-level": _stock([_AUD001_LOW], ["AUD-999"])},
    "stock-mixed-missing": {"query-stock-level": _stock([_CHG001_OK], ["CHG-999"])},
    "stock-sales-aud001": {
        "query-stock-level": _stock([_AUD001_LOW]),
        "query-sales": _sales([_SALES_AUD001_7]),
    },
    "stock-replenish-aud001": {
        "query-stock-level": _stock([_AUD001_LOW]),
        "suggest-replenish": _replenish([_REPL_AUD001]),
    },
    "sales-aud001-7": {"query-sales": _sales([_SALES_AUD001_7])},
    "sales-aud001-30": {"query-sales": _sales([_SALES_AUD001_30])},
    "sales-chg001-30": {"query-sales": _sales([_SALES_CHG001_30])},
    "sales-charging-30": {
        "query-sales": _sales([_SALES_CHG001_30, _SALES_CHG002_30, _SALES_CHG003_30])
    },
    "replenish-acc001": {"suggest-replenish": _replenish([_REPL_ACC001])},
    "replenish-acc001-cov30": {"suggest-replenish": _replenish([_REPL_ACC001_COV30])},
    "replenish-aud001": {"suggest-replenish": _replenish([_REPL_AUD001])},
    "replenish-aud002": {"suggest-replenish": _replenish([_REPL_AUD002])},
}

# ── live 种子：每个用例默认支持全部 3 个只读工具（参数感知、数据取自既有夹具目录）──

_STOCK_REGISTRY: dict[str, dict] = {
    item["sku"]: item
    for item in (_AUD001_LOW, _AUD002_LOW, _AUD003_LOW, _AUD004_LOW, _CHG001_OK)
}

_SALES_REGISTRY: dict[tuple[str, int], dict] = {
    ("AUD-001", 7): _SALES_AUD001_7,
    ("AUD-001", 30): _SALES_AUD001_30,
    ("CHG-001", 30): _SALES_CHG001_30,
}

_SALES_CATEGORY_ITEMS: dict[str, list[dict]] = {
    "audio": [_SALES_AUD001_30],
    "charging": [_SALES_CHG001_30, _SALES_CHG002_30, _SALES_CHG003_30],
}

_REPL_REGISTRY: dict[str, dict] = {
    "ACC-001": _REPL_ACC001,
    "AUD-001": _REPL_AUD001,
    "AUD-002": _REPL_AUD002,
}

_REPL_CATEGORY_ITEMS: dict[str, list[dict]] = {
    "audio": [_REPL_AUD001, _REPL_AUD002],
}


def _seed_stock(params: dict) -> dict:
    skus = params.get("skus")
    if isinstance(skus, list) and skus:
        items: list[dict] = []
        missing: list[str] = []
        for sku in skus:
            item = _STOCK_REGISTRY.get(sku) if isinstance(sku, str) else None
            if item is None:
                missing.append(sku)
            else:
                items.append(item)
        return _stock(items, missing)
    category = params.get("category")
    if isinstance(category, str) and category:
        return _stock([item for item in _STOCK_REGISTRY.values() if item["category"] == category])
    return _stock([])


def _seed_sales(params: dict) -> dict:
    sku = params.get("sku")
    if isinstance(sku, str) and sku:
        days = params.get("days")
        days = days if isinstance(days, int) else None
        item = (
            _SALES_REGISTRY.get((sku, days))
            or _SALES_REGISTRY.get((sku, 7))
            or _SALES_REGISTRY.get((sku, 30))
        )
        if item is None:
            return _sales([], [sku])
        return _sales([item])
    category = params.get("category")
    if isinstance(category, str) and category:
        return _sales(list(_SALES_CATEGORY_ITEMS.get(category, [])))
    return _sales([])


def _seed_replenish(params: dict) -> dict:
    skus = params.get("skus")
    if isinstance(skus, list) and skus:
        cover = params.get("targetCoverDays")
        items: list[dict] = []
        missing: list[str] = []
        for sku in skus:
            item = _REPL_REGISTRY.get(sku) if isinstance(sku, str) else None
            if item is None:
                missing.append(sku)
                continue
            if sku == "ACC-001" and cover == 30:
                item = _REPL_ACC001_COV30
            items.append(item)
        return _replenish(items, missing)
    category = params.get("category")
    if isinstance(category, str) and category:
        return _replenish(list(_REPL_CATEGORY_ITEMS.get(category, [])))
    return _replenish([])


LIVE_SEED_RESOLVERS: dict[str, Callable[[dict], dict]] = {
    "query-stock-level": _seed_stock,
    "query-sales": _seed_sales,
    "suggest-replenish": _seed_replenish,
}


def live_payloads(case: dict) -> dict:
    """live 夹具 = 三只读工具种子 ∪ 用例夹具（用例 java_fixture 优先覆盖同名工具）。"""
    payloads: dict[str, Any] = dict(LIVE_SEED_RESOLVERS)
    payloads.update(resolve_fixture(case))
    return payloads


class JavaStub:
    """本机假 Java：只读工具按夹具应答；写工具端点只记录触达、绝不执行。

    payload 支持 dict（原样返回）或 callable(params)（live 种子按请求参数应答）；
    未声明的工具名才回 404「未知工具」；tool_errors 支持 {\"down\": true} 模拟连接失败。
    """

    def __init__(
        self,
        payloads: dict[str, Any] | None = None,
        tool_errors: dict[str, dict] | None = None,
        down: bool = False,
    ) -> None:
        self.payloads = payloads or {}
        self.tool_errors = tool_errors or {}
        self.down = down
        self.requests: list[dict] = []
        self.write_touches: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        params = _read_json_object(request)
        self.requests.append({"path": path, "body": params})
        if self.down:
            raise httpx.ConnectError("connection refused", request=request)
        if path == "/health":
            return httpx.Response(200, json={"status": "up"})
        if path == "/api/agent/tools/schema":
            return httpx.Response(200, json=schema_payload())
        if path in WRITE_ENDPOINT_PATHS:
            self.write_touches.append(path)
            return httpx.Response(404, json={"code": 404, "message": "未知工具"})
        if path.startswith("/api/agent/tools/"):
            name = path.rsplit("/", 1)[1]
            error = self.tool_errors.get(name)
            if error is not None:
                if error.get("down"):
                    raise httpx.ConnectError(f"{name} 连接失败", request=request)
                return httpx.Response(
                    error["status"], json={"code": error["code"], "message": error["message"]}
                )
            payload = self.payloads.get(name)
            if payload is None:
                return httpx.Response(404, json={"code": 404, "message": "未知工具"})
            if callable(payload):
                payload = payload(params if isinstance(params, dict) else {})
            return httpx.Response(200, json={"code": 0, "message": "ok", "data": payload})
        return httpx.Response(404, json={"code": 404, "message": "not found"})

    def build_client(self) -> JavaToolClient:
        return JavaToolClient(base_url="http://java.eval", transport=httpx.MockTransport(self.handler))

    @property
    def tool_paths(self) -> list[str]:
        return [entry["path"] for entry in self.requests if entry["path"].startswith("/api/agent/tools/")]


def _read_json_object(request: httpx.Request) -> dict | None:
    try:
        raw = request.content
    except httpx.RequestNotRead:
        return None
    if not raw:
        return None
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    return body if isinstance(body, dict) else None


def load_cases(path: Path = CASES_PATH) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        text = f.read()
    cases: list[dict] = []
    for line_no, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            case = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path.name} 第 {line_no} 行不是合法 JSON：{exc}") from exc
        if not isinstance(case, dict):
            raise ValueError(f"{path.name} 第 {line_no} 行不是对象")
        cases.append(case)
    return cases


def validate_cases(cases: list[dict]) -> None:
    if len(cases) != TOTAL_CASES:
        raise ValueError(f"用例数必须为 {TOTAL_CASES}，实际 {len(cases)}")
    counts: dict[str, int] = {}
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("case_id", "<无 case_id>")
        missing = [field for field in REQUIRED_CASE_FIELDS if field not in case]
        if missing:
            raise ValueError(f"用例 {case_id} 缺少字段：{missing}")
        category = case["category"]
        if category not in CATEGORIES:
            raise ValueError(f"用例 {case_id} 的 category 非法：{category}")
        counts[category] = counts.get(category, 0) + 1
        prefix = CASE_ID_PREFIX[category]
        if not re.fullmatch(rf"{prefix}-\d{{3}}", case["case_id"]):
            raise ValueError(f"用例 {case_id} 的 case_id 不符合 {prefix}-NNN 命名")
        if case["case_id"] in seen:
            raise ValueError(f"case_id 重复：{case['case_id']}")
        seen.add(case["case_id"])
        if not isinstance(case["user_message"], str) or not case["user_message"].strip():
            raise ValueError(f"用例 {case_id} 的 user_message 为空")
        if case["deterministic_or_judged"] not in ("deterministic", "judged"):
            raise ValueError(f"用例 {case_id} 的 deterministic_or_judged 非法")
        if not isinstance(case["requires_real_model"], bool):
            raise ValueError(f"用例 {case_id} 的 requires_real_model 必须是布尔值")
        names = case["expected_tool_names"]
        if not isinstance(names, list) or not all(isinstance(item, str) for item in names):
            raise ValueError(f"用例 {case_id} 的 expected_tool_names 必须是字符串列表")
        params = case["expected_params"]
        if not isinstance(params, list):
            raise ValueError(f"用例 {case_id} 的 expected_params 必须是列表")
        if params and len(params) != len(names):
            raise ValueError(f"用例 {case_id} 的 expected_params 长度与 expected_tool_names 不一致")
        constraints = case["expected_answer_constraints"]
        if not isinstance(constraints, list) or not constraints:
            raise ValueError(f"用例 {case_id} 至少需要一条 expected_answer_constraints")
        for item in constraints:
            if not isinstance(item, str) or not item.startswith(CONSTRAINT_PREFIXES):
                raise ValueError(f"用例 {case_id} 的约束前缀非法：{item!r}")
        forbidden = case["forbidden_behaviors"]
        if not isinstance(forbidden, list) or not forbidden:
            raise ValueError(f"用例 {case_id} 至少需要一条 forbidden_behaviors")
        for token in forbidden:
            if token not in KNOWN_FORBIDDEN:
                raise ValueError(f"用例 {case_id} 的 forbidden token 未知：{token}")
        for token in ("touch_write_endpoints", "succeeded_write_tool_call"):
            if token not in forbidden:
                raise ValueError(f"用例 {case_id} 必须包含全局写红线 token：{token}")
        if case.get("java_fixture") is not None and case["java_fixture"] not in FIXTURES:
            raise ValueError(f"用例 {case_id} 引用了未知夹具：{case['java_fixture']}")
    for category, expected in CATEGORY_COUNTS.items():
        if counts.get(category, 0) != expected:
            raise ValueError(
                f"category {category} 用例数应为 {expected}，实际 {counts.get(category, 0)}"
            )


# ── knowledge.v1：RAG 知识检索套件（独立文件/独立报告/独立指标，不与 B0 混合）──
# groundedness 一律 judged：自动化只守结构与引用契约（sources 必须来自实际检索、
# chunk_id 真实存在、无命中不得返回 sources），「回答是否被文档支撑」转人工复核。

KNOWLEDGE_REQUIRED_FIELDS = REQUIRED_CASE_FIELDS + ("title", "expect_sources")
KNOWLEDGE_CASE_IDS = tuple(f"knowledge-{index:03d}" for index in range(1, KNOWLEDGE_TOTAL_CASES + 1))


def load_knowledge_cases(path: Path = KNOWLEDGE_CASES_PATH) -> list[dict]:
    return load_cases(path)


def validate_knowledge_cases(cases: list[dict]) -> None:
    if len(cases) != KNOWLEDGE_TOTAL_CASES:
        raise ValueError(f"knowledge 用例数必须为 {KNOWLEDGE_TOTAL_CASES}，实际 {len(cases)}")
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("case_id", "<无 case_id>")
        missing = [field for field in KNOWLEDGE_REQUIRED_FIELDS if field not in case]
        if missing:
            raise ValueError(f"knowledge 用例 {case_id} 缺少字段：{missing}")
        if case["category"] != KNOWLEDGE_CATEGORY:
            raise ValueError(f"knowledge 用例 {case_id} 的 category 必须为 {KNOWLEDGE_CATEGORY}")
        if not re.fullmatch(r"knowledge-\d{3}", case["case_id"]):
            raise ValueError(f"knowledge 用例 {case_id} 的 case_id 不符合 knowledge-NNN 命名")
        if case["case_id"] in seen:
            raise ValueError(f"knowledge case_id 重复：{case['case_id']}")
        seen.add(case["case_id"])
        if not isinstance(case["user_message"], str) or not case["user_message"].strip():
            raise ValueError(f"knowledge 用例 {case_id} 的 user_message 为空")
        if case["deterministic_or_judged"] != "judged":
            raise ValueError(f"knowledge 用例 {case_id} 必须标记 judged（groundedness 人工复核）")
        if not isinstance(case["requires_real_model"], bool):
            raise ValueError(f"knowledge 用例 {case_id} 的 requires_real_model 必须是布尔值")
        if not isinstance(case["expect_sources"], bool):
            raise ValueError(f"knowledge 用例 {case_id} 的 expect_sources 必须是布尔值")
        names = case["expected_tool_names"]
        if not isinstance(names, list) or not all(isinstance(item, str) for item in names):
            raise ValueError(f"knowledge 用例 {case_id} 的 expected_tool_names 必须是字符串列表")
        params = case["expected_params"]
        if not isinstance(params, list):
            raise ValueError(f"knowledge 用例 {case_id} 的 expected_params 必须是列表")
        if params and len(params) != len(names):
            raise ValueError(f"knowledge 用例 {case_id} 的 expected_params 长度与 expected_tool_names 不一致")
        constraints = case["expected_answer_constraints"]
        if not isinstance(constraints, list) or not constraints:
            raise ValueError(f"knowledge 用例 {case_id} 至少需要一条 expected_answer_constraints")
        for item in constraints:
            if not isinstance(item, str) or not item.startswith(CONSTRAINT_PREFIXES):
                raise ValueError(f"knowledge 用例 {case_id} 的约束前缀非法：{item!r}")
        forbidden = case["forbidden_behaviors"]
        if not isinstance(forbidden, list) or not forbidden:
            raise ValueError(f"knowledge 用例 {case_id} 至少需要一条 forbidden_behaviors")
        for token in forbidden:
            if token not in KNOWN_FORBIDDEN:
                raise ValueError(f"knowledge 用例 {case_id} 的 forbidden token 未知：{token}")
        for token in ("touch_write_endpoints", "succeeded_write_tool_call"):
            if token not in forbidden:
                raise ValueError(f"knowledge 用例 {case_id} 必须包含全局写红线 token：{token}")
        chinese = case.get("expected_chinese")
        if chinese is not None and (
            not isinstance(chinese, list) or not all(isinstance(item, str) for item in chinese)
        ):
            raise ValueError(f"knowledge 用例 {case_id} 的 expected_chinese 必须是字符串列表")
        if case.get("java_fixture") is not None and case["java_fixture"] not in FIXTURES:
            raise ValueError(f"knowledge 用例 {case_id} 引用了未知夹具：{case['java_fixture']}")
    if seen != set(KNOWLEDGE_CASE_IDS):
        raise ValueError(f"knowledge case_id 集合必须为 {list(KNOWLEDGE_CASE_IDS)}，实际 {sorted(seen)}")


def resolve_fixture(case: dict) -> dict[str, dict]:
    name = case.get("java_fixture")
    if not name:
        return {}
    return copy.deepcopy(FIXTURES[name])


def resolve_request_id(case: dict) -> str:
    return case.get("request_id") or f"req-eval-{case['case_id']}"


# ── live 期望覆盖（agent-baseline.v1.live.json）──────────────────────────────
# live 与 replay 共享用户问题与安全红线，但路径期望不同：live 不要求复刻 cassette
# 调用序列，只约束 required/allowed/forbidden 工具集、参数合法性与答案事实一致性。

LIVE_REQUIRED_FIELDS = (
    "required_tools",
    "allowed_tools",
    "forbidden_tools",
    "answer_constraints",
    "expected_language",
    "judged",
)
LIVE_OPTIONAL_FIELDS = ("params", "expected_chinese", "tool_failures_expected", "notes")
LIVE_KNOWN_FIELDS = frozenset(LIVE_REQUIRED_FIELDS) | frozenset(LIVE_OPTIONAL_FIELDS)
LIVE_LANGUAGES = frozenset({"zh"})


def load_live_overlay(path: Path = LIVE_CASES_PATH) -> dict[str, dict]:
    with open(path, encoding="utf-8") as f:
        payload = json.load(f)
    if not isinstance(payload, dict) or not isinstance(payload.get("cases"), dict):
        raise ValueError(f"live 期望文件必须是 {{\"cases\": {{...}}}} 对象：{path}")
    cases = payload["cases"]
    for case_id, entry in cases.items():
        if not isinstance(entry, dict):
            raise ValueError(f"live 期望 {case_id} 不是对象")
    return cases


def validate_live_overlay(cases: list[dict], overlay: dict[str, dict]) -> None:
    known_ids = {case["case_id"] for case in cases}
    unknown = sorted(set(overlay) - known_ids)
    missing = sorted(known_ids - set(overlay))
    if missing or unknown:
        raise ValueError(f"live 期望必须与用例 1:1 对应；缺少 {missing}，多余 {unknown}")
    for case_id, entry in overlay.items():
        extra = sorted(set(entry) - LIVE_KNOWN_FIELDS)
        if extra:
            raise ValueError(f"live 期望 {case_id} 含未知字段：{extra}")
        for field in LIVE_REQUIRED_FIELDS:
            if field not in entry:
                raise ValueError(f"live 期望 {case_id} 缺少字段：{field}")
        for field in ("required_tools", "allowed_tools", "forbidden_tools"):
            names = entry[field]
            if not isinstance(names, list) or not all(isinstance(item, str) for item in names):
                raise ValueError(f"live 期望 {case_id} 的 {field} 必须是字符串列表")
            illegal = sorted(set(names) - set(READONLY_TOOL_NAMES) - set(WRITE_TOOL_NAMES))
            if illegal:
                raise ValueError(f"live 期望 {case_id} 的 {field} 含未知工具：{illegal}")
        if set(entry["required_tools"]) & set(entry["forbidden_tools"]):
            raise ValueError(f"live 期望 {case_id} 的必需与禁止工具冲突")
        if set(entry["required_tools"]) - set(READONLY_TOOL_NAMES):
            raise ValueError(f"live 期望 {case_id} 只能要求只读工具")
        if set(entry["allowed_tools"]) - set(READONLY_TOOL_NAMES):
            raise ValueError(f"live 期望 {case_id} 的 allowed_tools 只能含只读工具")
        constraints = entry["answer_constraints"]
        if not isinstance(constraints, list) or not constraints:
            raise ValueError(f"live 期望 {case_id} 至少需要一条 answer_constraints")
        for item in constraints:
            if not isinstance(item, str) or not item.startswith(CONSTRAINT_PREFIXES):
                raise ValueError(f"live 期望 {case_id} 的约束前缀非法：{item!r}")
        if not isinstance(entry["judged"], bool):
            raise ValueError(f"live 期望 {case_id} 的 judged 必须是布尔值")
        if entry["expected_language"] not in LIVE_LANGUAGES:
            raise ValueError(
                f"live 期望 {case_id} 的 expected_language 必须是 {sorted(LIVE_LANGUAGES)} 之一"
            )
        params = entry.get("params")
        if params is not None:
            if not isinstance(params, dict) or not params:
                raise ValueError(f"live 期望 {case_id} 的 params 必须是非空对象")
            legal_tools = set(entry["required_tools"]) | set(entry["allowed_tools"])
            bad = sorted(set(params) - legal_tools)
            if bad:
                raise ValueError(f"live 期望 {case_id} 的 params 引用了未声明工具：{bad}")
        chinese = entry.get("expected_chinese")
        if chinese is not None and (
            not isinstance(chinese, list) or not all(isinstance(item, str) for item in chinese)
        ):
            raise ValueError(f"live 期望 {case_id} 的 expected_chinese 必须是字符串列表")
        failures = entry.get("tool_failures_expected")
        if failures is not None and (
            not isinstance(failures, list)
            or sorted(set(failures) - set(READONLY_TOOL_NAMES)) != []
        ):
            raise ValueError(f"live 期望 {case_id} 的 tool_failures_expected 只能是只读工具名")


# ── retrieval.v1：检索层确定性评测（无模型、无 Java、无 cassette）─────────────
# 只评价「检索 → 阈值 → top-k → 注入预算 → sources」这一段：期望 chunk 必须出现在
# 实际注入集内、无命中用例必须零命中零注入、顺序稳定、sources 与实际注入一致；
# 不涉及模型、回答质量与工具调用。期望 chunk_id 在加载时对语料索引校验，防静默漂移。

RETRIEVAL_CASES_PATH = EVALS_DIR / "cases" / "retrieval.v1.jsonl"
RETRIEVAL_CATEGORIES = ("hit", "multi", "no-hit", "boundary")
RETRIEVAL_TOTAL_CASES = 18
RETRIEVAL_REQUIRED_FIELDS = (
    "case_id",
    "category",
    "title",
    "query",
    "expected_chunk_ids",
    "expect_no_hit",
)


def load_retrieval_cases(path: Path = RETRIEVAL_CASES_PATH) -> list[dict]:
    return load_cases(path)


def validate_retrieval_cases(cases: list[dict], known_chunk_ids: set[str]) -> None:
    if len(cases) != RETRIEVAL_TOTAL_CASES:
        raise ValueError(f"retrieval 用例数必须为 {RETRIEVAL_TOTAL_CASES}，实际 {len(cases)}")
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("case_id", "<无 case_id>")
        missing = [field for field in RETRIEVAL_REQUIRED_FIELDS if field not in case]
        if missing:
            raise ValueError(f"retrieval 用例 {case_id} 缺少字段：{missing}")
        if case["category"] not in RETRIEVAL_CATEGORIES:
            raise ValueError(f"retrieval 用例 {case_id} 的 category 非法：{case['category']}")
        if not re.fullmatch(r"retrieval-\d{3}", case["case_id"]):
            raise ValueError(f"retrieval 用例 {case_id} 的 case_id 不符合 retrieval-NNN 命名")
        if case["case_id"] in seen:
            raise ValueError(f"retrieval case_id 重复：{case['case_id']}")
        seen.add(case["case_id"])
        if not isinstance(case["title"], str) or not case["title"].strip():
            raise ValueError(f"retrieval 用例 {case_id} 的 title 为空")
        if not isinstance(case["query"], str) or not case["query"].strip():
            raise ValueError(f"retrieval 用例 {case_id} 的 query 为空")
        expected = case["expected_chunk_ids"]
        if not isinstance(expected, list) or not all(isinstance(item, str) for item in expected):
            raise ValueError(f"retrieval 用例 {case_id} 的 expected_chunk_ids 必须是字符串列表")
        if not isinstance(case["expect_no_hit"], bool):
            raise ValueError(f"retrieval 用例 {case_id} 的 expect_no_hit 必须是布尔值")
        if case["expect_no_hit"]:
            if expected:
                raise ValueError(f"retrieval 用例 {case_id} 为无命中用例，expected_chunk_ids 必须为空")
        elif not expected:
            raise ValueError(f"retrieval 用例 {case_id} 必须至少一个 expected_chunk_ids")
        unknown = [chunk_id for chunk_id in expected if chunk_id not in known_chunk_ids]
        if unknown:
            raise ValueError(
                f"retrieval 用例 {case_id} 的期望 chunk 不在语料索引中：{unknown}"
                "（语料或分块已漂移，需人工复核用例）"
            )
        notes = case.get("notes")
        if notes is not None and not isinstance(notes, str):
            raise ValueError(f"retrieval 用例 {case_id} 的 notes 必须是字符串")
    wanted = {f"retrieval-{index:03d}" for index in range(1, RETRIEVAL_TOTAL_CASES + 1)}
    if seen != wanted:
        raise ValueError(f"retrieval case_id 集合必须为 retrieval-001..{RETRIEVAL_TOTAL_CASES:03d}，实际 {sorted(seen)}")


# ── retrieval.v2：v2 检索器召回/噪声评测（别名归一/标题分权/泛词停用/特征词准入/短查询）──
# 与 v1 数据集相互独立：每条非 no-hit 用例必须把全部语料 chunk 划入
# expected（必须注入）/ allowed（可容忍邻接，不计噪声）/ forbidden（出现即失败）三类，
# 三类互斥且并集 = 语料全集，防止出现「未分类 chunk」绕过噪声门禁。
# 标签为人工按语料语义写定；v1 的冻结用例与期望不受本套件影响。

RETRIEVAL_V2_CASES_PATH = EVALS_DIR / "cases" / "retrieval.v2.jsonl"
RETRIEVAL_V2_CATEGORIES = ("colloquial", "mixed", "short", "sku-value", "combo", "no-hit", "near")
RETRIEVAL_V2_TOTAL_CASES = 20
RETRIEVAL_V2_REQUIRED_FIELDS = (
    "case_id",
    "category",
    "title",
    "query",
    "expected_chunk_ids",
    "allowed_chunk_ids",
    "forbidden_chunk_ids",
    "expect_no_hit",
    "notes",
)


def load_retrieval_v2_cases(path: Path = RETRIEVAL_V2_CASES_PATH) -> list[dict]:
    return load_cases(path)


def _v2_id_list(case_id: str, case: dict, field: str) -> list[str]:
    value = case[field]
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"retrieval.v2 用例 {case_id} 的 {field} 必须是字符串列表")
    return value


def validate_retrieval_v2_cases(cases: list[dict], known_chunk_ids: set[str]) -> None:
    if len(cases) != RETRIEVAL_V2_TOTAL_CASES:
        raise ValueError(f"retrieval.v2 用例数必须为 {RETRIEVAL_V2_TOTAL_CASES}，实际 {len(cases)}")
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("case_id", "<无 case_id>")
        missing = [field for field in RETRIEVAL_V2_REQUIRED_FIELDS if field not in case]
        if missing:
            raise ValueError(f"retrieval.v2 用例 {case_id} 缺少字段：{missing}")
        if case["category"] not in RETRIEVAL_V2_CATEGORIES:
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 category 非法：{case['category']}")
        if not re.fullmatch(r"retrieval-v2-\d{3}", case["case_id"]):
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 case_id 不符合 retrieval-v2-NNN 命名")
        if case["case_id"] in seen:
            raise ValueError(f"retrieval.v2 case_id 重复：{case['case_id']}")
        seen.add(case["case_id"])
        if not isinstance(case["title"], str) or not case["title"].strip():
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 title 为空")
        if not isinstance(case["query"], str) or not case["query"].strip():
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 query 为空")
        if not isinstance(case["notes"], str) or not case["notes"].strip():
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 notes 为空（人工标签必须写明依据）")
        expected = _v2_id_list(case_id, case, "expected_chunk_ids")
        allowed = _v2_id_list(case_id, case, "allowed_chunk_ids")
        forbidden = _v2_id_list(case_id, case, "forbidden_chunk_ids")
        if not isinstance(case["expect_no_hit"], bool):
            raise ValueError(f"retrieval.v2 用例 {case_id} 的 expect_no_hit 必须是布尔值")
        all_ids = expected + allowed + forbidden
        unknown = [chunk_id for chunk_id in all_ids if chunk_id not in known_chunk_ids]
        if unknown:
            raise ValueError(
                f"retrieval.v2 用例 {case_id} 的 chunk 不在语料索引中：{unknown}"
                "（语料或分块已漂移，需人工复核用例）"
            )
        if case["expect_no_hit"]:
            if all_ids:
                raise ValueError(
                    f"retrieval.v2 用例 {case_id} 为无命中用例，expected/allowed/forbidden 必须全空"
                )
        else:
            if not expected:
                raise ValueError(f"retrieval.v2 用例 {case_id} 必须至少一个 expected_chunk_ids")
            groups = [set(expected), set(allowed), set(forbidden)]
            if sum(len(group) for group in groups) != len(set(all_ids)):
                raise ValueError(
                    f"retrieval.v2 用例 {case_id} 的 expected/allowed/forbidden 三类必须互斥"
                )
            if set(all_ids) != known_chunk_ids:
                missing_ids = sorted(known_chunk_ids - set(all_ids))
                raise ValueError(
                    f"retrieval.v2 用例 {case_id} 未覆盖全部语料 chunk，缺少分类：{missing_ids}"
                )
    wanted = {f"retrieval-v2-{index:03d}" for index in range(1, RETRIEVAL_V2_TOTAL_CASES + 1)}
    if seen != wanted:
        raise ValueError(
            f"retrieval.v2 case_id 集合必须为 retrieval-v2-001..{RETRIEVAL_V2_TOTAL_CASES:03d}，实际 {sorted(seen)}"
        )


# ── retrieval.shadow：v1/v2 检索器对照基准（同一批人工写定查询，两侧逐条实跑）──────
# 独立资产，供第三方仅凭仓库复现 v1/v2 受控词法对照；不改 retrieval.v1 / retrieval.v2
# 的用例与断言。标签（expected/allowed/forbidden）为人工按语料语义写定；本套件只做
# 测量，不做单侧质量门禁（v1 为冻结对照、故意保留较弱行为）。

RETRIEVAL_SHADOW_CASES_PATH = EVALS_DIR / "cases" / "retrieval.shadow.v1.jsonl"
RETRIEVAL_SHADOW_CATEGORIES = RETRIEVAL_V2_CATEGORIES
RETRIEVAL_SHADOW_TOTAL_CASES = 20
RETRIEVAL_SHADOW_REQUIRED_FIELDS = RETRIEVAL_V2_REQUIRED_FIELDS


def load_retrieval_shadow_cases(path: Path = RETRIEVAL_SHADOW_CASES_PATH) -> list[dict]:
    return load_cases(path)


def _shadow_id_list(case_id: str, case: dict, field: str) -> list[str]:
    value = case[field]
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"retrieval.shadow 用例 {case_id} 的 {field} 必须是字符串列表")
    return value


def validate_retrieval_shadow_cases(cases: list[dict], known_chunk_ids: set[str]) -> None:
    if len(cases) != RETRIEVAL_SHADOW_TOTAL_CASES:
        raise ValueError(
            f"retrieval.shadow 用例数必须为 {RETRIEVAL_SHADOW_TOTAL_CASES}，实际 {len(cases)}"
        )
    seen: set[str] = set()
    for case in cases:
        case_id = case.get("case_id", "<无 case_id>")
        missing = [field for field in RETRIEVAL_SHADOW_REQUIRED_FIELDS if field not in case]
        if missing:
            raise ValueError(f"retrieval.shadow 用例 {case_id} 缺少字段：{missing}")
        if case["category"] not in RETRIEVAL_SHADOW_CATEGORIES:
            raise ValueError(
                f"retrieval.shadow 用例 {case_id} 的 category 非法：{case['category']}"
            )
        if not re.fullmatch(r"retrieval-shadow-\d{3}", case["case_id"]):
            raise ValueError(
                f"retrieval.shadow 用例 {case_id} 的 case_id 不符合 retrieval-shadow-NNN 命名"
            )
        if case["case_id"] in seen:
            raise ValueError(f"retrieval.shadow case_id 重复：{case['case_id']}")
        seen.add(case["case_id"])
        if not isinstance(case["title"], str) or not case["title"].strip():
            raise ValueError(f"retrieval.shadow 用例 {case_id} 的 title 为空")
        if not isinstance(case["query"], str) or not case["query"].strip():
            raise ValueError(f"retrieval.shadow 用例 {case_id} 的 query 为空")
        if not isinstance(case["notes"], str) or not case["notes"].strip():
            raise ValueError(
                f"retrieval.shadow 用例 {case_id} 的 notes 为空（人工标签必须写明依据）"
            )
        expected = _shadow_id_list(case_id, case, "expected_chunk_ids")
        allowed = _shadow_id_list(case_id, case, "allowed_chunk_ids")
        forbidden = _shadow_id_list(case_id, case, "forbidden_chunk_ids")
        if not isinstance(case["expect_no_hit"], bool):
            raise ValueError(f"retrieval.shadow 用例 {case_id} 的 expect_no_hit 必须是布尔值")
        all_ids = expected + allowed + forbidden
        unknown = [chunk_id for chunk_id in all_ids if chunk_id not in known_chunk_ids]
        if unknown:
            raise ValueError(
                f"retrieval.shadow 用例 {case_id} 的 chunk 不在语料索引中：{unknown}"
                "（语料或分块已漂移，需人工复核用例）"
            )
        if case["expect_no_hit"]:
            if all_ids:
                raise ValueError(
                    f"retrieval.shadow 用例 {case_id} 为无命中用例，expected/allowed/forbidden 必须全空"
                )
        else:
            if not expected:
                raise ValueError(
                    f"retrieval.shadow 用例 {case_id} 必须至少一个 expected_chunk_ids"
                )
            groups = [set(expected), set(allowed), set(forbidden)]
            if sum(len(group) for group in groups) != len(set(all_ids)):
                raise ValueError(
                    f"retrieval.shadow 用例 {case_id} 的 expected/allowed/forbidden 三类必须互斥"
                )
    wanted = {
        f"retrieval-shadow-{index:03d}"
        for index in range(1, RETRIEVAL_SHADOW_TOTAL_CASES + 1)
    }
    if seen != wanted:
        raise ValueError(
            "retrieval.shadow case_id 集合必须为 "
            f"retrieval-shadow-001..{RETRIEVAL_SHADOW_TOTAL_CASES:03d}，实际 {sorted(seen)}"
        )
