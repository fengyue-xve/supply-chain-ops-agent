"""RAG 检索器单测：分词、命中与阈值、top_k、预算、降级、顺序与日志脱敏。

知识目录用 tmp_path 合成以控制检索输入，不依赖真实语料内容。
"""

import logging

import pytest

from app.rag.documents import DEFAULT_KNOWLEDGE_DIR
from app.rag.retriever import (
    MIN_SCORE,
    PROMPT_BUDGET_CHARS,
    TOP_K,
    EmptyRetriever,
    KnowledgeRetriever,
    render_chunk,
    select_for_prompt,
    tokenize,
)

DEFAULT_DOC = """# 供应链运营知识

## [replenish-formula] 补货公式
补货建议公式：建议补货量 = 覆盖天数 × 日均销量 + 安全库存 - 当前库存；结果小于零时按零处理。

## [purchase-approval] 采购单与人工确认
创建采购单只是生成待人工确认的单据，确认动作必须由人工完成，助手不得代替人工确认。

## [missing-skus] missingSkus 语义
missingSkus 表示请求的 SKU 中没有库存记录的部分，属于正常的空结果语义。
"""

TIE_DOC = """# 平局文档

## [tie-a] 库存
库存说明文字。

## [tie-b] 库存
库存说明文字。
"""


@pytest.fixture
def knowledge_dir(tmp_path):
    base = tmp_path / "knowledge"
    base.mkdir()
    (base / "supply-ops.zh.md").write_text(DEFAULT_DOC, encoding="utf-8")
    return base


def test_tokenize_mixes_han_bigrams_and_ascii_tokens():
    terms = tokenize("AUD-001 库存")
    assert "aud-001" in terms
    assert "库存" in terms
    assert all(term == term.lower() for term in terms)


def test_tokenize_handles_single_char_runs_and_trailing_dots():
    assert tokenize("库") == ()
    assert tokenize("库存") == ("库存",)
    assert tokenize("v1.") == ("v1",)


def test_chinese_query_hits_the_matching_section(knowledge_dir):
    hits = KnowledgeRetriever(knowledge_dir).search("补货公式是什么")
    assert hits
    assert hits[0].chunk.section_id == "replenish-formula"
    assert hits[0].score >= MIN_SCORE


def test_ascii_query_hits_the_matching_section(knowledge_dir):
    hits = KnowledgeRetriever(knowledge_dir).search("missingSkus 表示什么")
    assert hits
    assert hits[0].chunk.section_id == "missing-skus"


def test_irrelevant_query_returns_empty(knowledge_dir):
    assert KnowledgeRetriever(knowledge_dir).search("今天天气怎么样") == []


def test_single_non_ascii_overlap_is_rejected_by_gate(knowledge_dir):
    # "AUD-001 库存" 与知识段仅共享一个中文词项：按宁缺毋滥阈值整体丢弃（零注入）
    assert KnowledgeRetriever(knowledge_dir).search("AUD-001 库存") == []


def test_select_for_prompt_caps_top_k(tmp_path):
    doc = "# 多段\n\n" + "".join(
        f"## [s{i}] 库存说明{i}\n\n库存说明文字共同内容。\n\n" for i in range(5)
    )
    (tmp_path / "doc.md").write_text(doc, encoding="utf-8")
    hits = KnowledgeRetriever(tmp_path).search("库存说明")
    assert len(hits) == 5
    selected = select_for_prompt(hits)
    assert [chunk.chunk_id for chunk in selected] == ["doc#s0-1", "doc#s1-1", "doc#s2-1"]
    assert TOP_K == 3


def test_select_for_prompt_respects_explicit_budget(tmp_path):
    (tmp_path / "doc.md").write_text(TIE_DOC, encoding="utf-8")
    hits = KnowledgeRetriever(tmp_path).search("库存说明")
    assert len(hits) == 2
    first = len(render_chunk(hits[0].chunk))
    second = len(render_chunk(hits[1].chunk))
    assert select_for_prompt(hits, budget=first - 1) == []
    assert len(select_for_prompt(hits, budget=first + 2 + second - 1)) == 1
    assert len(select_for_prompt(hits, budget=first + 2 + second)) == 2


def test_select_for_prompt_stays_within_default_budget(tmp_path):
    body = "库存说明" * 122
    doc = "# 预算\n\n" + "".join(
        f"## [s{i}] 库存说明{i}\n\n{body}\n\n" for i in range(5)
    )
    (tmp_path / "doc.md").write_text(doc, encoding="utf-8")
    hits = KnowledgeRetriever(tmp_path).search("库存说明")
    assert len(hits) == 5
    selected = select_for_prompt(hits)
    rendered = sum(len(render_chunk(chunk)) for chunk in selected)
    rendered += 2 * (len(selected) - 1)
    assert rendered <= PROMPT_BUDGET_CHARS
    assert 1 <= len(selected) < len(hits)


def test_missing_directory_retriever_is_degraded_and_logs_once(tmp_path, caplog):
    with caplog.at_level(logging.WARNING, logger="app.rag"):
        retriever = KnowledgeRetriever(tmp_path / "not-there")
        retriever.search("库存")
    assert retriever.status == "missing-dir"
    degraded = [
        record.getMessage()
        for record in caplog.records
        if "rag_index_degraded" in record.getMessage()
    ]
    assert len(degraded) == 1
    assert "missing-dir" in degraded[0]


def test_empty_directory_retriever_is_degraded(tmp_path):
    retriever = KnowledgeRetriever(tmp_path)
    assert retriever.status == "empty"
    assert retriever.search("库存档位") == []


def test_broken_utf8_directory_retriever_is_degraded(tmp_path):
    (tmp_path / "broken.md").write_bytes(b"## [x] \xff\xfe")
    retriever = KnowledgeRetriever(tmp_path)
    assert retriever.status == "read-error"
    assert retriever.search("库存档位") == []


def test_healthy_retriever_status_is_ok(knowledge_dir):
    retriever = KnowledgeRetriever(knowledge_dir)
    assert retriever.status == "ok"
    assert retriever.chunks


def test_only_top_level_markdown_of_the_directory_is_indexed(tmp_path):
    base = tmp_path / "knowledge"
    base.mkdir()
    (base / "inside.md").write_text("# 内\n\n## [in] 内\n\n内部内容。\n", encoding="utf-8")
    (tmp_path / "outside.md").write_text(
        "# 外\n\n## [out] 外\n\n外部内容。\n", encoding="utf-8"
    )
    nested = base / "deeper"
    nested.mkdir()
    (nested / "nested.md").write_text(
        "# 嵌\n\n## [nest] 嵌\n\n嵌套内容。\n", encoding="utf-8"
    )
    retriever = KnowledgeRetriever(base)
    assert [chunk.chunk_id for chunk in retriever.chunks] == ["inside#in-1"]


def test_tied_scores_keep_stable_index_order(tmp_path):
    (tmp_path / "doc.md").write_text(TIE_DOC, encoding="utf-8")
    retriever = KnowledgeRetriever(tmp_path)
    first = [hit.chunk.chunk_id for hit in retriever.search("库存说明")]
    second = [hit.chunk.chunk_id for hit in retriever.search("库存说明")]
    assert first == second == ["doc#tie-a-1", "doc#tie-b-1"]


def test_search_log_contains_ids_only_not_query_or_chunk_body(knowledge_dir, caplog):
    retriever = KnowledgeRetriever(knowledge_dir)
    secret = "PRIVATE-Q-MARKER"
    with caplog.at_level(logging.INFO, logger="app.rag"):
        hits = retriever.search(f"{secret} 补货公式", request_id="req-rag-log-001")
    assert hits
    messages = [record.getMessage() for record in caplog.records]
    joined = "\n".join(messages)
    assert any(message.startswith("rag_retrieve") for message in messages)
    assert "req-rag-log-001" in joined
    assert "replenish-formula" in joined  # 只允许 request_id / 命中数 / chunk_id
    assert "private-q-marker" not in joined.lower()
    assert "建议补货量" not in joined  # chunk 正文不落日志


def test_empty_retriever_returns_no_hits():
    retriever = EmptyRetriever()
    assert retriever.status == "empty"
    assert retriever.search("库存") == []


def test_snapshot_date_semantics_explainable_from_default_corpus():
    hits = KnowledgeRetriever(DEFAULT_KNOWLEDGE_DIR).search("库存快照的 snapDate 是以什么为准的？")
    assert hits
    top = hits[0].chunk
    assert top.section_id == "stock-snapshot"
    assert "snapDate" in top.text
    assert "为准" in top.text and "本文档" in top.text


def test_specific_sku_value_questions_are_zero_hit_on_default_corpus():
    retriever = KnowledgeRetriever(DEFAULT_KNOWLEDGE_DIR)
    assert retriever.search("AUD-001 现在库存多少？") == []
    assert retriever.search("AUD-001 现在库存多少？近 7 天又卖了多少？") == []
