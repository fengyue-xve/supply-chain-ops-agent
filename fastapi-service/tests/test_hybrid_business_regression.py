"""Seen, frozen business labels become regression gates, never unseen evaluation claims."""
import json
from pathlib import Path
import pytest
from app.rag.hybrid import HybridKnowledgeRetriever

CASES=[]
for filename in ['current-business.v1.jsonl','current-business.holdout.v1.jsonl']:
    CASES.extend(json.loads(line) for line in (Path(__file__).resolve().parents[1]/'evals/cases'/filename).read_text(encoding='utf-8').splitlines() if line.strip())

@pytest.mark.parametrize('case',CASES,ids=[case['case_id'] for case in CASES])
def test_hybrid_respects_frozen_business_relevance_and_forbidden_chunks(case):
    retriever=HybridKnowledgeRetriever()
    ids={chunk.chunk_id for chunk in retriever.select_for_prompt(retriever.search(case['query']))}
    if case['expect_no_hit']: assert not ids
    else:
        assert set(case['expected_chunk_ids']).issubset(ids)
        assert not set(case['forbidden_chunk_ids']) & ids
