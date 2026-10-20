"""Independent lexical holdout audit. Exit 1 keeps failures visible; no model or network."""
import argparse
import json
import hashlib
from pathlib import Path
from app.rag.retriever_v3 import KnowledgeRetrieverV3
from app.rag.documents import DEFAULT_KNOWLEDGE_DIR
from evals.run import _run_retrieval_v2_case
from evals.metrics import aggregate_retrieval_v2
from evals.current_business import corpus_manifest

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out")
    args = parser.parse_args()
    path = Path(__file__).parent / "cases/current-business.holdout.v1.jsonl"
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    retriever = KnowledgeRetrieverV3()
    rows = [_run_retrieval_v2_case(c, retriever) for c in cases]
    metrics = aggregate_retrieval_v2(rows)
    report = {"meta": {"suite": "current-business.holdout.v1", "dataset_role": "holdout_audit", "corpus_kind": "current-application", "model": None, "cases_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "corpus_manifest": corpus_manifest(DEFAULT_KNOWLEDGE_DIR)}, "metrics": metrics, "cases": rows, "failures": [r for r in rows if r["status"] == "failed"]}
    print("Independent holdout:", metrics["passed_cases"], "/", metrics["total_cases"])
    for row in report["failures"]:
        print("FAIL", row["case_id"], [c["detail"] for c in row["checks"] if not c["ok"]])
    print("Lexical retrieval only; no model quality claim. Failures are retained.")
    if args.out:
        output = Path(args.out)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return 1 if report["failures"] else 0

if __name__ == "__main__":
    raise SystemExit(main())
