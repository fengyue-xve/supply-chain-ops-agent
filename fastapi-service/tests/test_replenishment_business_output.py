from app.agent.render import render_tool_result
from app.rag.documents import load_knowledge_index, DEFAULT_KNOWLEDGE_DIR


def test_missing_sales_is_not_rendered_as_zero_order():
    result = render_tool_result("suggest-replenish", {"items": [{"sku": "DEMO-01", "suggestedQty": None, "avgDailyQty": None, "assessmentStatus": "insufficient_data", "reason": "未查询到销量"}]})
    assert "数据不足" in result
    assert "建议补货 0" not in result
    assert "None" not in result


def test_reference_suggestion_retains_dates_and_review_warning():
    result = render_tool_result("suggest-replenish", {"items": [{"sku": "DEMO-01", "suggestedQty": 120, "currentQty": 45, "avgDailyQty": 10, "urgency": "urgent", "assessmentStatus": "review_required", "stockSnapDate": "2026-09-02", "salesWindowStart": "2026-08-28", "salesWindowEnd": "2026-09-03", "reason": "库存与销量业务日期不一致"}]})
    assert "120" in result and "2026-09-02" in result and "2026-09-03" in result
    assert "需人工复核" in result and "不计预留与在途" in result


def test_current_knowledge_uses_product_strategy_and_pack_rounding():
    chunks = load_knowledge_index(DEFAULT_KNOWLEDGE_DIR).chunks
    formula = next(c.text for c in chunks if c.section_id == "replenish-formula")
    assert "不是固定值" in formula and "pkgQty" in formula and "不重复相加" in formula
    assert "rawGap" in formula and "不计预留与在途" in formula


def test_current_business_queries_retrieve_actual_rule_sections():
    from app.rag.retriever_v2 import KnowledgeRetrieverV2
    retriever = KnowledgeRetrieverV2()
    cases = [
        ("补货建议的数量怎么算", "replenish-formula"),
        ("目标覆盖天数和提前期的关系", "replenish-params"),
        ("补货紧急度", "replenish-urgency"),
        ("补货与销量参数", "replenish-params"),
        ("安全库存和箱规", "replenish-formula"),
        ("库存快照日期", "stock-snapshot"),
    ]
    for query, required in cases:
        selected = retriever.select_for_prompt(retriever.search(query))
        assert required in {chunk.section_id for chunk in selected}, query


def test_historical_replay_corpus_is_explicitly_separate_from_application():
    from evals.support import FROZEN_KNOWLEDGE_DIR
    assert FROZEN_KNOWLEDGE_DIR.resolve() != DEFAULT_KNOWLEDGE_DIR.resolve()
    historical = (FROZEN_KNOWLEDGE_DIR / "supply-ops.zh.md").read_text(encoding="utf-8")
    current = (DEFAULT_KNOWLEDGE_DIR / "supply-ops.zh.md").read_text(encoding="utf-8")
    assert "安全库存是固定值 20" in historical
    assert "安全库存是固定值 20" not in current
