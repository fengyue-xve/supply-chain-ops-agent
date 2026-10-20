"""V3 adds corpus-derived lexical anchors to V2 candidates, without model/embeddings.

V2 remains frozen for historical replay. A high shared-word score alone can inject
unrelated material after a corpus change. V3 retains candidates supported by a
title phrase (>=3 Han characters), a selective ASCII field, or the longest shared
Han phrase across candidates. With no such anchor, the rarest candidate-overlap
term is required. No query IDs, section IDs, or expected answers enter selection.
"""
from __future__ import annotations

from collections import Counter
import logging
import re

from app.rag.retriever_v2 import KnowledgeRetrieverV2, apply_aliases, query_terms

logger = logging.getLogger("app.rag")
_HAN = re.compile(r"[一-鿿]+")


def normalize_query(query: str) -> str:
    # Explicit vocabulary synonym, longest first. The corpus uses 缺省.
    return apply_aliases((query or "").replace("默认值", "缺省").replace("默认", "缺省"))


def _phrase_length(query: str, text: str) -> int:
    """Longest exact Han phrase, capped to 12 characters for bounded work."""
    runs = _HAN.findall(query[:2000])
    for size in range(min(12, max(map(len, runs), default=0)), 2, -1):
        if any(run[i:i+size] in text for run in runs for i in range(len(run)-size+1)):
            return size
    return 0


class KnowledgeRetrieverV3(KnowledgeRetrieverV2):
    def __init__(self, directory=None):
        super().__init__(directory)
        self._frequency = Counter(term for terms in self._term_sets for term in terms)

    def search(self, query: str, request_id: str = ""):
        normalized = normalize_query(query)
        hits = super().search(normalized, request_id=request_id)
        if len(hits) < 2:
            return hits
        terms = query_terms(normalized)
        # Product identifiers, numbers and tool names do not automatically become
        # selective fields: an anchor must occur in <= one quarter of the corpus.
        selective_ascii = {
            t for t in terms if t.isascii() and any(c.isalpha() for c in t)
            and 0 < self._frequency[t] <= max(1, len(self.chunks) // 4)
        }
        phrases = [_phrase_length(normalized, h.chunk.title + "\n" + h.chunk.text) for h in hits]
        strongest = max(phrases, default=0)
        # Equal-length generic phrases (e.g. 补货建议) do not outrank a more
        # selective phrase (e.g. 箱规取整). Derive rarity from this corpus.
        strongest_phrases = {run[i:i+strongest] for run in _HAN.findall(normalized[:2000])
                             for i in range(len(run)-strongest+1)} if strongest >= 3 else set()
        phrase_frequency = {p: sum(p in c.title + "\n" + c.text for c in self.chunks)
                            for p in strongest_phrases}
        phrase_frequency = {p: count for p, count in phrase_frequency.items() if count}
        rarest_phrase = min(phrase_frequency.values(), default=0)
        salient_phrases = {p for p, count in phrase_frequency.items() if count == rarest_phrase}
        overlap_terms = set().union(*(terms & self._term_sets[self.chunks.index(h.chunk)] for h in hits))
        lexical = {t for t in overlap_terms if not t.isascii() or any(c.isalpha() for c in t)}
        rarest = min((self._frequency[t] for t in lexical), default=0)
        kept = []
        for hit, length in zip(hits, phrases):
            idx = self.chunks.index(hit.chunk)
            title_anchor = _phrase_length(normalized, hit.chunk.title) >= 3
            field_anchor = bool(selective_ascii & self._term_sets[idx])
            phrase_anchor = strongest >= 3 and any(p in hit.chunk.title + "\n" + hit.chunk.text for p in salient_phrases)
            fallback = strongest < 3 and not selective_ascii and any(
                self._frequency[t] == rarest for t in terms & self._term_sets[idx] & lexical
            )
            if title_anchor or field_anchor or phrase_anchor or fallback:
                kept.append(hit)
        logger.info("rag_anchor request_id=%s before=%d after=%d retriever=v3",
                    request_id or "-", len(hits), len(kept))
        return kept
