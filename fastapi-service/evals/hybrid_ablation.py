"""Frozen-label ablation on the actual current corpus; no relabeling or model-accuracy claim."""
from __future__ import annotations
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from app.rag.documents import DEFAULT_KNOWLEDGE_DIR
from app.rag.retriever import KnowledgeRetriever
from app.rag.retriever_v2 import KnowledgeRetrieverV2
from app.rag.retriever_v3 import KnowledgeRetrieverV3
from app.rag.hybrid import HybridKnowledgeRetriever, DIMENSIONS, RRF_K


def evaluate(retriever, cases):
    rows = []
    for case in cases:
        selected = retriever.select_for_prompt(retriever.search(case["query"]))
        ids = [chunk.chunk_id for chunk in selected]
        expected = set(case["expected_chunk_ids"])
        forbidden = set(case["forbidden_chunk_ids"])
        covered = len(expected & set(ids))
        noise = sorted(set(ids) & forbidden)
        ok = not ids if case["expect_no_hit"] else covered == len(expected) and not noise
        rows.append({"case_id": case["case_id"], "selected_ids": ids, "expected": sorted(expected), "covered": covered,
                     "expected_count": len(expected), "forbidden_injected": noise, "negative": case["expect_no_hit"], "strict_pass": ok})
    positive = [row for row in rows if not row["negative"]]
    expected_count = sum(row["expected_count"] for row in positive)
    return {"strict_pass": sum(row["strict_pass"] for row in rows), "total": len(rows),
            "recall_at_3": sum(row["covered"] for row in positive) / expected_count if expected_count else 1.0,
            "all_expected_covered": sum(row["covered"] == row["expected_count"] for row in positive),
            "positive_count": len(positive), "forbidden_injections": sum(len(row["forbidden_injected"]) for row in rows),
            "clean_negatives": sum(row["negative"] and not row["selected_ids"] for row in rows), "cases": rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="../artifacts/hybrid-ablation.json")
    args = parser.parse_args()
    base = Path(__file__).resolve().parent
    datasets = {"development_30": base / "cases/current-business.v1.jsonl", "seen_rephrasing_12": base / "cases/current-business.holdout.v1.jsonl"}
    factories = {"Lexical v1": KnowledgeRetriever, "Lexical v2": KnowledgeRetrieverV2,
                 "Lexical v3 (10-21 baseline)": KnowledgeRetrieverV3, "Hybrid RAG": HybridKnowledgeRetriever}
    report = {"generated_at": datetime.now(timezone.utc).isoformat(), "scope": "current corpus; 3 chunks and 1500 character prompt budget",
              "embedding": {"model": "domain-feature-hash-v1", "trained_model": False, "dimensions": DIMENSIONS, "rrf_k": RRF_K},
              "limitations": ["Both sets have been inspected; rephrasing is a regression audit, not an unseen holdout.",
                              "Local deterministic feature vectors are not a trained semantic embedding model.",
                              "Recall measures labeled retrieval, not LLM answer quality. Failures and noise remain visible."],
              "corpus_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(DEFAULT_KNOWLEDGE_DIR.glob('*.md'))}, "datasets": {}}
    for name, path in datasets.items():
        cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        dataset = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "results": {}}
        for label, factory in factories.items():
            result = evaluate(factory(), cases)
            dataset["results"][label] = result
            print(f'{name} | {label}: strict={result["strict_pass"]}/{result["total"]}; recall@3={result["recall_at_3"]:.3f}; forbidden={result["forbidden_injections"]}')
        report["datasets"][name] = dataset
    target = Path(args.out); target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
