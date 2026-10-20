"""RAG 检索 v2：受控别名归一 + 标题分权 + 泛词停用 + 特征词准入 + 短查询准入。

与 v1（app/rag/retriever.py）的关系：v1 行为已冻结（retrieval.v1 评测与 knowledge.v1 引用
契约依赖其输出），本模块是生产链路采用的 v2，两条实现并存、各自被独立评测。v2 复用 v1 的
tokenize/render_chunk/阈值/top-k/预算常量和加载校验，只替换查询归一、评分与准入、预算选择：

1. 别名归一（_ALIASES）：口语/别名短语按「最长优先」替换为语料规范业务词（查询侧，不改语料）；
2. 泛词停用（_GENERIC_TERMS）：无区分度高频词不参与评分与准入；
3. 标题分权：score = (重叠词数 + 标题重叠词数) / 查询词数（标题命中权重加倍），
   同分按标题命中数优先、再同分保持语料顺序（稳定排序）；
4. 准入：score ≥ MIN_SCORE 且满足其一 —— 标题命中 / 重叠 ≥2 词 / ASCII 命中 / 命中特征词；
5. 短查询准入：单 bigram / 单 token 查询按第 4 条处理（「档位」「缺货」等特征词可单命中）；
6. 预算选择 skip-continue：按分数降序扫描，放不下跳过继续（v1 为首个放不下即停）。

规则全部为人工维护的显式表（别名/泛词/特征词），可解释、可单测；仍是纯词法检索，
表外同义改写可能零命中，不宣称语义理解能力。
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Sequence

from app.rag.documents import KnowledgeChunk
from app.rag.retriever import (
    MIN_SCORE,
    PROMPT_BUDGET_CHARS,
    TOP_K,
    KnowledgeRetriever,
    RetrievalHit,
    _STOP_TERMS,
    render_chunk,
    tokenize,
)

logger = logging.getLogger("app.rag")

# 口语/别名短语 → 语料规范业务词（长匹配优先）。替换目标必须是语料中实际存在的词，
# 否则归一没有意义；表是人工资产，新增条目须同步补用例。
_ALIASES: tuple[tuple[str, str], ...] = tuple(
    sorted(
        (
            ("卖得怎么样", "销量"),
            ("卖得如何", "销量"),
            ("卖得好吗", "销量"),
            ("卖得怎样", "销量"),
            ("怎么补货", "补货建议"),
            ("要补多少", "补货建议"),
            ("怎么补", "补货建议"),
            ("补多少", "补货建议"),
            ("没货", "缺货"),
            ("无货", "缺货"),
            ("断货", "缺货"),
            ("售罄", "缺货"),
            ("数据时点", "snapDate"),
            ("快照日期", "snapDate"),
            ("实时流水", "快照"),
            ("类别", "类目"),
        ),
        key=lambda pair: -len(pair[0]),
    )
)

# 高频泛词：在语料中跨小节出现、几乎不携带区分度；停用后不参与评分与准入。
# 「没有」为否定功能词（「有没有 X」类口语），主题词是 X，故停用。
_GENERIC_TERMS = frozenset(
    {"查询", "系统", "信息", "现在", "目前", "时候", "内容", "结果", "商品", "没有"}
)

# 特征词（业务专名）：与语料小节名/字段名对应；单个特征词命中即可准入（短查询准入的关键）。
_FEATURE_TERMS = frozenset(
    {"缺货", "档位", "快照", "销量", "补货", "采购", "确认", "类目", "异动", "流水", "覆盖"}
)


def apply_aliases(text: str) -> str:
    normalized = text or ""
    for alias, canonical in _ALIASES:
        if alias in normalized:
            normalized = normalized.replace(alias, canonical)
    return normalized


def query_terms(query: str) -> frozenset[str]:
    normalized = apply_aliases(query)
    return frozenset(
        term
        for term in tokenize(normalized)
        if term not in _STOP_TERMS and term not in _GENERIC_TERMS
    )


class KnowledgeRetrieverV2(KnowledgeRetriever):
    """v2 检索器：子类化 v1 复用索引加载与防御，覆盖归一/评分/准入/预算选择。"""

    def __init__(self, directory: Path | str | None = None) -> None:
        super().__init__(directory)
        self._title_terms: tuple[frozenset[str], ...] = tuple(
            frozenset(tokenize(chunk.title)) for chunk in self._chunks
        )

    def search(self, query: str, request_id: str = "") -> list[RetrievalHit]:
        terms = query_terms(query)
        ranked: list[tuple[RetrievalHit, int]] = []
        if terms:
            for index, chunk in enumerate(self._chunks):
                overlap = terms & self._term_sets[index]
                if not overlap:
                    continue
                title_overlap = overlap & self._title_terms[index]
                score = (len(overlap) + len(title_overlap)) / len(terms)
                ascii_hits = sum(1 for term in overlap if term.isascii())
                admitted = (
                    title_overlap
                    or len(overlap) >= 2
                    or ascii_hits >= 1
                    or (overlap & _FEATURE_TERMS)
                )
                if score >= MIN_SCORE and admitted:
                    ranked.append(
                        (RetrievalHit(chunk=chunk, score=round(score, 4)), len(title_overlap))
                    )
        # 稳定排序：分数优先，同分比标题命中数，仍并列保持语料顺序（确定性）。
        ranked.sort(key=lambda item: (-item[0].score, -item[1]))
        hits = [hit for hit, _ in ranked]
        logger.info(
            "rag_retrieve request_id=%s hits=%d chunk_ids=%s retriever=v2",
            request_id or "-",
            len(hits),
            [hit.chunk.chunk_id for hit in hits],
        )
        return hits

    def select_for_prompt(
        self,
        hits: Sequence[RetrievalHit],
        *,
        top_k: int = TOP_K,
        budget: int = PROMPT_BUDGET_CHARS,
    ) -> list[KnowledgeChunk]:
        """skip-continue：按分数降序扫描 top_k 内命中，放不下跳过继续；保序、确定性。"""
        selected: list[KnowledgeChunk] = []
        used = 0
        for hit in hits[: max(top_k, 0)]:
            cost = len(render_chunk(hit.chunk)) + (2 if selected else 0)
            if cost > budget - used:
                continue
            selected.append(hit.chunk)
            used += cost
        return selected
