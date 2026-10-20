"""Current business corpus retrieval audit; no model or Java calls.

Labels are authored from business section responsibilities, not generated from hits.
Known lexical noise remains a failing result: it is never relabelled as allowed.
"""
from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from app.rag.documents import DEFAULT_KNOWLEDGE_DIR
from app.rag.retriever import MIN_SCORE, TOP_K, PROMPT_BUDGET_CHARS
from app.rag.retriever_v2 import KnowledgeRetrieverV2
from app.rag.retriever_v3 import KnowledgeRetrieverV3
from evals.metrics import aggregate_retrieval_v2, scan_for_secrets
from evals.support import EVALS_DIR, REPORTS_DIR, load_retrieval_v2_cases

SUITE = "current-business.v1"
CASES_PATH = EVALS_DIR / "cases" / f"{SUITE}.jsonl"
CATEGORIES = {"strategy", "coverage", "parameters", "dates", "sales", "approval", "lookup", "negative"}
KNOWN_LIMITS = {
    "current-business-001": "库存档位共享库存/固定/20字面，不能作为商品安全库存策略的答案依据。",
    "current-business-005": "紧急度/实时边界共享补货、库存、公式泛词，不能解释预留与在途是否计入。",
    "current-business-013": "公式与紧急度只有补货词面，不能回答skus/category互斥。",
    "current-business-014": "异动小节共享快照与销量窗口，但不能解释日期不一致的处理。",
    "current-business-018": "补货参数共享销量/天数，不能解释日均分母。",
    "current-business-023": "异动小节共享SKU/库存，不能解释missingSkus与零库存的区别。",
}


def load_cases() -> list[dict]:
    return [json.loads(line) for line in CASES_PATH.read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_cases(cases: list[dict], known_ids: set[str]) -> None:
    if len(cases) != 30:
        raise ValueError("current-business.v1 必须包含30条独立业务查询")
    if {case.get("case_id") for case in cases} != {f"current-business-{i:03d}" for i in range(1, 31)}:
        raise ValueError("current-business.v1 case_id必须唯一且连续")
    for case in cases:
        for field in ("query", "title", "notes", "category"):
            if not isinstance(case.get(field), str) or not case[field].strip():
                raise ValueError(f"{case['case_id']}: {field}为空")
        if case["category"] not in CATEGORIES or type(case.get("expect_no_hit")) is not bool:
            raise ValueError(f"{case['case_id']}: 分类或expect_no_hit非法")
        groups = []
        for field in ("expected_chunk_ids", "allowed_chunk_ids", "forbidden_chunk_ids"):
            values = case.get(field)
            if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
                raise ValueError(f"{case['case_id']}: {field}必须为字符串列表")
            if len(set(values)) != len(values):
                raise ValueError(f"{case['case_id']}: {field}重复")
            groups.append(set(values))
        expected, allowed, forbidden = groups
        if any(group - known_ids for group in groups):
            raise ValueError(f"{case['case_id']}: 标签不在当前语料索引中")
        if expected & allowed or expected & forbidden or allowed & forbidden:
            raise ValueError(f"{case['case_id']}: 标签必须互斥")
        if case["expect_no_hit"]:
            if any(groups) or case["category"] != "negative":
                raise ValueError(f"{case['case_id']}: 无关负样本标签必须全空")
        elif not expected or set.union(*groups) != known_ids:
            raise ValueError(f"{case['case_id']}: 必须指定expected并对全部当前语料分类")


def corpus_manifest(directory: Path) -> list[dict]:
    return [{"file": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in sorted(directory.glob("*.md")) if p.is_file()]


def legacy_audit(retriever, runner) -> list[dict]:
    """Apply old labels to current corpus only as drift evidence, never old-suite score."""
    reviews = {
        "retrieval-v2-001": ("valid-adjacency", "新紧急度明确解释缺货优先复核，与缺货含义有业务关联；历史标签仍保持不变。"),
        "retrieval-v2-002": ("valid-adjacency", "断货处理与新紧急度优先复核有关；历史标签仍保持不变。"),
        "retrieval-v2-008": ("lexical-noise", "missingSkus仅提到快照中不存在，不能解释实时流水与快照的区别。"),
        "retrieval-v2-012": ("lexical-noise", "快照短词召回missingSkus属于共享词面，缺失SKU不是快照定义。"),
        "retrieval-v2-015": ("lexical-noise", "实时数据边界只提到计算公式等泛词，不能回答覆盖期与建议数量关系。"),
    }
    output = []
    for case in load_retrieval_v2_cases():
        result = runner(case, retriever)
        if result["status"] == "failed":
            kind, reason = reviews.get(case["case_id"], ("unreviewed-drift", "新增漂移，需要单独人工审查。"))
            output.append({"case_id": case["case_id"], "classification": kind, "reason": reason,
                           "injected_ids": result["summary"]["injected_ids"],
                           "failed_checks": [c for c in result["checks"] if not c["ok"]]})
    return output


def run(args) -> int:
    # Deferred import keeps the existing historical runner and assertions unchanged.
    from evals.run import _run_retrieval_v2_case, _git_head
    if args.live:
        print("[配置错误] current-business.v1 是当前语料离线检索检查，不支持 --live")
        return 2
    directory = Path(os.environ.get("KNOWLEDGE_DIR") or DEFAULT_KNOWLEDGE_DIR)
    retriever = KnowledgeRetrieverV3(directory)
    try:
        cases = load_cases()
        validate_cases(cases, {c.chunk_id for c in retriever.chunks})
        if retriever.status != "ok":
            raise ValueError(f"当前语料加载异常: {retriever.status}")
    except (ValueError, OSError) as exc:
        print(f"[配置错误] {exc}")
        return 2
    selected = cases
    if args.case_id:
        wanted = set(args.case_id)
        if wanted - {c["case_id"] for c in cases}:
            print("[配置错误] 未知case_id")
            return 2
        selected = [c for c in selected if c["case_id"] in wanted]
    if args.category:
        if args.category not in CATEGORIES:
            print("[配置错误] 未知分类")
            return 2
        selected = [c for c in selected if c["category"] == args.category]
    if not selected:
        print("[配置错误] 过滤后没有可执行用例")
        return 2
    print(f"当前业务语料检索 | {SUITE} | 无模型/无Java/无网络 | 用例 {len(selected)}条")
    print(f"Current application corpus: {directory}; historical replay corpus is NOT used.")
    results = [_run_retrieval_v2_case(c, retriever) for c in selected]
    for result in results:
        marker = "PASS" if result["status"] == "passed" else "FAIL"
        print(f"[{marker}] {result['case_id']} {result['title']}")
        for check in result["checks"]:
            if not check["ok"]:
                print(f"  {check['name']}: {check['detail']}")
    metrics = aggregate_retrieval_v2(results)
    # Rename a nonstandard relevance diagnostic: it is not standard precision@k.
    metrics["allowed_injection_rate_mean"] = metrics.pop("precision_at_k")
    print(f"通过 {metrics['passed_cases']} / {metrics['total_cases']}，失败 {metrics['failed_cases']}")
    print(f"期望全部覆盖 {metrics['expected_covered_cases']}/{metrics['hit_cases']}；"
          f"无关零命中 {metrics['no_hit_clean_cases']}/{metrics['no_hit_cases']}；"
          f"forbidden注入 {metrics['noise_injected_total']}次")
    print("口径：人工业务标签的受控词法检查；不构成模型回答质量、语义检索质量或业务收益结论。已知噪声仍失败，不放宽门禁。")
    if not args.no_report:
        report = {
            "meta": {"report_schema_version": 4, "suite": SUITE, "mode": "offline-retrieval",
                     "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                     "base_commit": _git_head(), "model": None, "corpus_kind": "current-application",
                     "corpus_dir": directory.name, "corpus_manifest": corpus_manifest(directory),
                     "corpus_chunks": len(retriever.chunks), "retriever_status": retriever.status,
                     "cases_file": f"evals/cases/{SUITE}.jsonl", "case_count": len(results),
                     "cases_sha256": hashlib.sha256(CASES_PATH.read_bytes()).hexdigest(),
                     "top_k": TOP_K, "min_score": MIN_SCORE, "budget_chars": PROMPT_BUDGET_CHARS,
                     "note": "Current corpus only. Known failures retain strict business labels and CLI exit 1. No model/Java/network; no live answer quality claim."},
            "metrics": metrics, "cases": results,
            "failures": [r for r in results if r["status"] == "failed"],
            "v2_baseline_limitations": KNOWN_LIMITS,
            "known_limitations": {"lexical_only": "No semantic retrieval claim; see separate current-business.holdout.v1 audit for remaining recall/noise limitations."},
            "historical_labels_on_current_corpus_audit": legacy_audit(KnowledgeRetrieverV2(directory), _run_retrieval_v2_case),
        }
        serialized = json.dumps(report, ensure_ascii=False, indent=2)
        if scan_for_secrets(serialized):
            print("[拒绝写出] 报告敏感扫描命中")
            return 3
        path = Path(args.out) if args.out else REPORTS_DIR / f"{datetime.now():%Y%m%d-%H%M%S}-{SUITE}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(serialized + "\n", encoding="utf-8")
        print(f"报告：{path}")
    return 0 if metrics["failed_cases"] == 0 else 1
