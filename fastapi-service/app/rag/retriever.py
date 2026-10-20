"""内存词法检索：中文字符 bigram + 归一化 ASCII token（零新依赖）。

- 每次 chat 在内存执行检索，无命中返回空列表（调用方零注入）；
- top_k=3、注入预算 ≤1500 字符、低于阈值的命中一律丢弃（宁缺毋滥）；
- 索引与词项表加载后保持不可变；检索结果按分数降序、同分按索引顺序稳定排序；
- 检索日志只允许 request_id、命中数、chunk_id 与异常类型：不记录用户全文与 chunk 正文。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from app.rag.documents import (
    DEFAULT_KNOWLEDGE_DIR,
    KnowledgeChunk,
    load_knowledge_index,
)

logger = logging.getLogger("app.rag")

TOP_K = 3
PROMPT_BUDGET_CHARS = 1500
MIN_SCORE = 0.25

_HAN_RUN_RE = re.compile(r"[一-鿿]+")
_ASCII_TOKEN_RE = re.compile(r"[a-z0-9][a-z0-9_\-\.]*")

# 高频疑问/语气 bigram：不携带业务信息，参与匹配只会放大噪声
_STOP_TERMS = frozenset(
    {"什么", "是什", "么意", "意思", "怎么", "么样", "如何", "哪些", "哪个",
     "多少", "是否", "可以", "一下", "请问", "帮我"}
)


@dataclass(frozen=True)
class RetrievalHit:
    chunk: KnowledgeChunk
    score: float


def tokenize(text: str) -> tuple[str, ...]:
    lowered = (text or "").lower()
    terms: list[str] = []
    for run in _HAN_RUN_RE.findall(lowered):
        terms.extend(run[index : index + 2] for index in range(len(run) - 1))
    for raw in _ASCII_TOKEN_RE.findall(lowered):
        token = raw.rstrip(".")
        if token:
            terms.append(token)
    return tuple(terms)


def render_chunk(chunk: KnowledgeChunk) -> str:
    return f"[{chunk.chunk_id}] {chunk.title}\n{chunk.text}"


def select_for_prompt(
    hits: Sequence[RetrievalHit],
    *,
    top_k: int = TOP_K,
    budget: int = PROMPT_BUDGET_CHARS,
) -> list[KnowledgeChunk]:
    """取前 top_k 命中并按预算截断（从尾部整块丢弃，不做字符级裁剪）。"""
    selected: list[KnowledgeChunk] = []
    used = 0
    for hit in hits[: max(top_k, 0)]:
        cost = len(render_chunk(hit.chunk)) + (2 if selected else 0)
        if selected and used + cost > budget:
            break
        if not selected and cost > budget:
            break
        selected.append(hit.chunk)
        used += cost
    return selected


class KnowledgeRetriever:
    def __init__(self, directory: Path | str | None = None) -> None:
        self.directory = Path(directory) if directory is not None else DEFAULT_KNOWLEDGE_DIR
        self._index = load_knowledge_index(self.directory)
        self._chunks: tuple[KnowledgeChunk, ...] = self._index.chunks
        self._term_sets: tuple[frozenset[str], ...] = tuple(
            frozenset(tokenize(f"{chunk.title}\n{chunk.text}")) for chunk in self._chunks
        )
        if self._index.degraded:
            logger.warning(
                "rag_index_degraded reason=%s directory=%s chunks=%d",
                self._index.degraded,
                self._index.directory,
                len(self._chunks),
            )

    @property
    def status(self) -> str:
        return self._index.degraded or "ok"

    @property
    def chunks(self) -> tuple[KnowledgeChunk, ...]:
        return self._chunks

    def search(self, query: str, request_id: str = "") -> list[RetrievalHit]:
        terms = frozenset(term for term in tokenize(query) if term not in _STOP_TERMS)
        hits: list[RetrievalHit] = []
        if terms:
            for index, chunk in enumerate(self._chunks):
                overlap = terms & self._term_sets[index]
                if not overlap:
                    continue
                score = len(overlap) / len(terms)
                ascii_hits = sum(1 for term in overlap if term.isascii())
                if score >= MIN_SCORE and (len(overlap) >= 2 or ascii_hits >= 1):
                    hits.append(RetrievalHit(chunk=chunk, score=round(score, 4)))
        hits.sort(key=lambda hit: -hit.score)
        logger.info(
            "rag_retrieve request_id=%s hits=%d chunk_ids=%s",
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
        """实例方法：与模块级函数同语义（v1 break 截断）；v2 检索器覆盖此方法换选择策略。"""
        return select_for_prompt(hits, top_k=top_k, budget=budget)


class EmptyRetriever:
    """空检索器：测试与 B0 评测显式注入，保证零注入（与 RAG 接入前的消息逐字节一致）。"""

    status = "empty"

    def search(self, query: str, request_id: str = "") -> list[RetrievalHit]:
        return []

    def select_for_prompt(
        self,
        hits: Sequence[RetrievalHit],
        *,
        top_k: int = TOP_K,
        budget: int = PROMPT_BUDGET_CHARS,
    ) -> list[KnowledgeChunk]:
        return select_for_prompt(hits, top_k=top_k, budget=budget)
