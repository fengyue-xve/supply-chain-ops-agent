"""Reproducible local feature embeddings + lexical anchors + reciprocal rank fusion.

This default is an explicit domain-feature vectorizer, not a trained semantic model.
It has no network, query IDs, relevance labels, chunk IDs or expected answers.
"""
from __future__ import annotations

from collections import Counter
import hashlib
import math
import re
from typing import Sequence

from app.rag.retriever import RetrievalHit
from app.rag.retriever_v3 import KnowledgeRetrieverV3

DIMENSIONS = 512
RRF_K = 60
CONCEPTS = (
    ("safety", ("safetyqty", "安全库存", "安全量", "缓冲库存")),
    ("lead", ("leaddays", "提前期", "采购交期", "采购周期", "供货周期")),
    ("coverage", ("targetcoverdays", "覆盖天数", "覆盖期", "保护期")),
    ("package", ("pkgqty", "箱规", "整箱", "包装倍数", "装箱规格")),
    ("gap", ("rawgap", "缺口", "缺额", "短缺数量")),
    ("review", ("assessmentstatus", "review_required", "需复核", "待复核", "评估状态")),
    ("urgency", ("urgency", "紧急度", "紧急程度", "风险线")),
    ("snapshot", ("snapdate", "stocksnapdate", "库存快照", "数据时点")),
    ("sales_window", ("saleswindowend", "saleswindowstart", "销量窗口", "自然日", "日均")),
    ("missing", ("missingskus", "未找到", "查不到", "缺失sku")),
    ("approval", ("confirm-po", "create-purchase-order", "采购确认", "采购意向", "批准")),
    ("allocation", ("预留", "在途", "可分配", "实时数据")),
)


def features(text: str) -> Counter[str]:
    normalized = (text or "").casefold()
    result: Counter[str] = Counter()
    for term in re.findall(r"[a-z][a-z0-9_-]*", normalized):
        result["field:" + term] += 2.0
    for run in re.findall(r"[一-鿿]+", normalized):
        for size in (2, 3):
            for index in range(len(run) - size + 1):
                result["han:" + run[index:index + size]] += 0.35
    for concept, terms in CONCEPTS:
        if any(term in normalized for term in terms):
            result["concept:" + concept] += 4.0
    return result


class LocalFeatureEmbedding:
    model_id = "domain-feature-hash-v1"
    dimensions = DIMENSIONS

    def __init__(self, documents: Sequence[str]):
        bags = [features(text) for text in documents]
        counts = Counter(feature for bag in bags for feature in bag)
        self.idf = {feature: math.log(1 + len(bags) / (1 + count)) + 1 for feature, count in counts.items()}

    def embed(self, text: str) -> tuple[float, ...]:
        vector = [0.0] * self.dimensions
        for feature, weight in features(text).items():
            digest = hashlib.sha256(feature.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimensions
            vector[index] += (1 if digest[4] % 2 else -1) * math.log1p(weight) * self.idf.get(feature, 1.0)
        norm = math.sqrt(sum(value * value for value in vector))
        return tuple(value / norm if norm else 0.0 for value in vector)


def reciprocal_rank_fusion(rankings: Sequence[Sequence[str]], k: int = RRF_K) -> list[tuple[str, float]]:
    scores: dict[str, float] = {}
    order: dict[str, int] = {}
    for ranking in rankings:
        for rank, key in enumerate(dict.fromkeys(ranking), 1):
            order.setdefault(key, len(order))
            scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank)
    return sorted(scores.items(), key=lambda item: (-item[1], order[item[0]]))


class HybridKnowledgeRetriever(KnowledgeRetrieverV3):
    """Keeps the four lexical rules/anchor guards and the existing 3-chunk/1500-char budget."""
    def __init__(self, directory=None, embedder=None):
        super().__init__(directory)
        texts = [chunk.title + "\n" + chunk.text for chunk in self.chunks]
        self.embedder = embedder or LocalFeatureEmbedding(texts)
        self._vectors = tuple(self.embedder.embed(text) for text in texts)
        self.last_degraded = False

    def search(self, query: str, request_id: str = "") -> list[RetrievalHit]:
        lexical = super().search(query, request_id=request_id)
        self.last_degraded = False
        try:
            vector = self.embedder.embed(query)
            if len(vector) != self.embedder.dimensions or any(not math.isfinite(value) for value in vector):
                raise ValueError("invalid_embedding")
            ranked = sorted(((chunk, sum(a * b for a, b in zip(vector, embedded)))
                             for chunk, embedded in zip(self.chunks, self._vectors)), key=lambda item: -item[1])
            best = ranked[0][1] if ranked else 0
            threshold = max(0.16, best * 0.60)
            vector_hits = [chunk for chunk, score in ranked if score >= threshold][:6]
            query_fields = {term for term in re.findall(r"[a-z][a-z0-9_-]*", query.casefold())
                            if any("field:" + term in features(chunk.text) for chunk in self.chunks)}
            if query_fields:
                # Field questions need at least one actual field in the document,
                # plus coverage of all named domain concepts. This is derived from
                # the query/corpus, never relevance labels or section identifiers.
                concepts = {term for term in features(query) if term.startswith("concept:")}
                def admitted(chunk):
                    bag = features(chunk.title + "\n" + chunk.text)
                    return any("field:" + term in bag for term in query_fields) and concepts.issubset(bag)
                vector_hits = [chunk for chunk in vector_hits if admitted(chunk)]
                lexical_ids = [hit.chunk.chunk_id for hit in lexical if admitted(hit.chunk)]
                if not vector_hits and not lexical_ids:
                    return lexical
            elif lexical:
                # Preserve the existing high-precision natural-language candidate
                # set; vectors rerank it instead of injecting loosely related text.
                lexical_ids = [hit.chunk.chunk_id for hit in lexical]
                vector_hits = [chunk for chunk in vector_hits if chunk.chunk_id in lexical_ids]
            else:
                # Only a strong vector match can extend zero-hit lexical retrieval.
                vector_hits = [chunk for chunk, score in ranked if score >= max(0.50, best * 0.90)][:2]
                lexical_ids = []
            fused = reciprocal_rank_fusion([lexical_ids, [chunk.chunk_id for chunk in vector_hits]])
            chunks = {chunk.chunk_id: chunk for chunk in self.chunks}
            return [RetrievalHit(chunks[key], score) for key, score in fused]
        except (OSError, ValueError, RuntimeError, TypeError, ArithmeticError):
            self.last_degraded = True
            return lexical
