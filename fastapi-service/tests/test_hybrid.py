import math
from app.rag.hybrid import LocalFeatureEmbedding, HybridKnowledgeRetriever, reciprocal_rank_fusion


def test_vectors_are_normalized_reproducible_and_zero_for_empty():
    left = LocalFeatureEmbedding(["安全库存 safetyQty", "箱规 pkgQty"])
    right = LocalFeatureEmbedding(["安全库存 safetyQty", "箱规 pkgQty"])
    assert left.embed("缓冲库存") == right.embed("缓冲库存")
    assert math.isclose(sum(value * value for value in left.embed("安全库存")), 1)
    assert not any(left.embed(""))


def test_rrf_prefers_agreement_and_deduplicates_each_list():
    result = reciprocal_rank_fusion([["a", "b", "a"], ["c", "b"]])
    assert result[0][0] == "b"
    assert len(result) == 3
    assert reciprocal_rank_fusion([]) == []


def test_prompt_budget_and_reference_ids_are_preserved():
    retriever = HybridKnowledgeRetriever()
    hits = retriever.search("商品安全库存和箱规怎样参与补货？")
    chunks = retriever.select_for_prompt(hits)
    from app.rag.retriever import render_chunk
    assert len(chunks) <= 3
    assert len("\n\n".join(map(render_chunk, chunks))) <= 1500
    assert all(chunk in retriever.chunks for chunk in chunks)


def test_vector_failure_degrades_to_the_existing_lexical_path():
    retriever = HybridKnowledgeRetriever()
    from app.rag.retriever_v3 import KnowledgeRetrieverV3
    expected = KnowledgeRetrieverV3().search("箱规取整会改变紧急度吗")
    retriever.embedder.embed = lambda query: (float("nan"),) * retriever.embedder.dimensions
    assert retriever.search("箱规取整会改变紧急度吗") == expected
    assert retriever.last_degraded


def test_unrelated_empty_query_does_not_inject_anything():
    retriever = HybridKnowledgeRetriever()
    assert retriever.search("") == []
