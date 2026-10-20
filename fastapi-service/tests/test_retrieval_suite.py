"""retrieval.v1 检索层评测测试：用例集校验、逐条判定、指标聚合与 CLI 端到端。

全部走进程内静态语料与纯函数：无模型、无 Java、无网络、无 cassette。
"""

import copy
import io
import json
from contextlib import redirect_stdout

import pytest
from evals.support import FROZEN_KNOWLEDGE_DIR

from app.rag.retriever import KnowledgeRetriever
from evals import run as evals_run
from evals.metrics import (
    aggregate_retrieval,
    evaluate_retrieval_case,
    recall_at_k,
    reciprocal_rank,
)
from evals.support import (
    RETRIEVAL_CASES_PATH,
    RETRIEVAL_TOTAL_CASES,
    load_retrieval_cases,
    validate_retrieval_cases,
)


@pytest.fixture(scope="module")
def retriever() -> KnowledgeRetriever:
    return KnowledgeRetriever(FROZEN_KNOWLEDGE_DIR)


@pytest.fixture(scope="module")
def known_ids(retriever) -> set[str]:
    return {chunk.chunk_id for chunk in retriever.chunks}


def _cases() -> list[dict]:
    return load_retrieval_cases()


def _observation(**overrides) -> dict:
    base = {
        "hit_ids": ["supply-ops.zh#a-1"],
        "hit_scores": [0.5],
        "injected_ids": ["supply-ops.zh#a-1"],
        "injected_budget_used": 100,
        "second_pass_ids": ["supply-ops.zh#a-1"],
        "second_pass_scores": [0.5],
        "source_ids": ["supply-ops.zh#a-1"],
    }
    base.update(overrides)
    return base


def _hit_case(*chunk_ids: str) -> dict:
    return {
        "case_id": "retrieval-099",
        "category": "hit",
        "title": "合成用例",
        "query": "q",
        "expected_chunk_ids": list(chunk_ids) or ["supply-ops.zh#a-1"],
        "expect_no_hit": False,
    }


def _checks_by_name(case: dict, observation: dict) -> dict[str, dict]:
    return {check["name"]: check for check in evaluate_retrieval_case(case, observation)}


# ---------------------------------------------------------------- 用例集与校验


def test_frozen_cases_load_and_validate(known_ids):
    assert RETRIEVAL_CASES_PATH.exists()
    cases = _cases()
    assert len(cases) == RETRIEVAL_TOTAL_CASES == 18
    assert [case["case_id"] for case in cases] == [
        f"retrieval-{index:03d}" for index in range(1, 19)
    ]
    assert {case["category"] for case in cases} == {"hit", "multi", "no-hit", "boundary"}
    validate_retrieval_cases(cases, known_ids)


def _drop_one(cases):
    cases.pop()


def _unknown_chunk(cases):
    cases[0]["expected_chunk_ids"] = ["supply-ops.zh#nope-1"]


def _no_hit_with_expected(cases):
    no_hit = next(case for case in cases if case["expect_no_hit"])
    no_hit["expected_chunk_ids"] = ["supply-ops.zh#stock-level-1"]


def _missing_field(cases):
    del cases[0]["query"]


def _bad_category(cases):
    cases[0]["category"] = "other"


def _bad_naming(cases):
    cases[0]["case_id"] = "case-001"


def _duplicate_id(cases):
    cases[1]["case_id"] = cases[0]["case_id"]


@pytest.mark.parametrize(
    ("mutator", "keyword"),
    [
        (_drop_one, "用例数必须为"),
        (_unknown_chunk, "不在语料索引"),
        (_no_hit_with_expected, "必须为空"),
        (_missing_field, "缺少字段"),
        (_bad_category, "category 非法"),
        (_bad_naming, "命名"),
        (_duplicate_id, "重复"),
    ],
)
def test_validation_rejects_broken_cases(known_ids, mutator, keyword):
    cases = copy.deepcopy(_cases())
    mutator(cases)
    with pytest.raises(ValueError) as excinfo:
        validate_retrieval_cases(cases, known_ids)
    assert keyword in str(excinfo.value)


# ------------------------------------------------------------------ 逐条判定


def test_all_checks_pass_on_consistent_observation():
    checks = evaluate_retrieval_case(_hit_case(), _observation())
    assert {check["name"] for check in checks} == {
        "expected_injected",
        "top_k",
        "budget",
        "threshold",
        "order_stable",
        "sources_consistency",
    }
    assert all(check["ok"] is True for check in checks)


def test_missing_expected_chunk_is_flagged():
    checks = _checks_by_name(
        _hit_case("supply-ops.zh#a-1", "supply-ops.zh#b-1"), _observation()
    )
    failed = [name for name, check in checks.items() if check["ok"] is False]
    assert failed == ["expected_injected"]
    assert "supply-ops.zh#b-1" in checks["expected_injected"]["detail"]


def test_sources_mismatch_and_order_drift_are_flagged():
    obs = _observation(
        source_ids=["supply-ops.zh#other-1"],
        second_pass_ids=["supply-ops.zh#b-1"],
        second_pass_scores=[0.4],
    )
    checks = _checks_by_name(_hit_case(), obs)
    assert checks["sources_consistency"]["ok"] is False
    assert checks["order_stable"]["ok"] is False
    assert checks["expected_injected"]["ok"] is True


def test_threshold_budget_and_top_k_are_flagged():
    checks = _checks_by_name(_hit_case(), _observation(hit_scores=[0.1]))
    assert checks["threshold"]["ok"] is False

    checks = _checks_by_name(_hit_case(), _observation(injected_budget_used=5000))
    assert checks["budget"]["ok"] is False

    four_ids = [f"supply-ops.zh#c-{index}" for index in range(4)]
    checks = _checks_by_name(
        _hit_case("supply-ops.zh#c-0"),
        _observation(injected_ids=four_ids, source_ids=four_ids),
    )
    assert checks["top_k"]["ok"] is False
    assert checks["sources_consistency"]["ok"] is True


def test_no_hit_case_requires_zero_hits_and_rejects_any():
    case = {**_hit_case(), "expect_no_hit": True, "expected_chunk_ids": []}
    clean_obs = _observation(
        hit_ids=[],
        hit_scores=[],
        injected_ids=[],
        injected_budget_used=0,
        second_pass_ids=[],
        second_pass_scores=[],
        source_ids=[],
    )
    clean = _checks_by_name(case, clean_obs)
    assert "no_hit" in clean and "expected_injected" not in clean
    assert all(check["ok"] is True for check in clean.values())

    dirty = _checks_by_name(case, _observation())
    assert dirty["no_hit"]["ok"] is False
    assert "expected_injected" not in dirty


# ---------------------------------------------------------------- 指标纯函数


def test_recall_at_k_units():
    assert recall_at_k([], ["supply-ops.zh#a-1"]) is None
    assert recall_at_k(["a", "b"], ["b", "a", "c"]) == 1.0
    assert recall_at_k(["a", "b"], ["b"]) == 0.5
    assert recall_at_k(["a"], ["b"]) == 0.0


def test_reciprocal_rank_units():
    assert reciprocal_rank(["a"], ["a"]) == 1.0
    assert reciprocal_rank(["b"], ["a", "b", "c"]) == 0.5
    assert reciprocal_rank(["z"], ["a", "b"]) == 0.0
    assert reciprocal_rank([], ["a"]) == 0.0


def _synthetic_result(case_id: str, *, no_hit: bool = False, ok: bool = True) -> dict:
    names = (["no_hit"] if no_hit else ["expected_injected"]) + [
        "top_k",
        "budget",
        "threshold",
        "order_stable",
        "sources_consistency",
    ]
    return {
        "case_id": case_id,
        "category": "no-hit" if no_hit else "hit",
        "title": "合成",
        "status": "passed" if ok else "failed",
        "duration_ms": 0,
        "checks": [{"name": name, "ok": ok, "detail": ""} for name in names],
        "summary": {
            "expect_no_hit": no_hit,
            "expected_chunk_ids": [],
            "hit_ids": [],
            "injected_ids": [],
            "injected_budget_used": 0,
            "retriever_status": "ok",
            "corpus_chunks": 12,
            "recall_at_k": None if no_hit else 1.0,
            "reciprocal_rank": 1.0,
            "top1_hit": ok,
        },
    }


def test_aggregate_retrieval_metrics_and_gates():
    results = [
        _synthetic_result("retrieval-001"),
        _synthetic_result("retrieval-002"),
        _synthetic_result("retrieval-003", no_hit=True),
    ]
    metrics = aggregate_retrieval(results)
    assert metrics["total_cases"] == 3
    assert metrics["passed_cases"] == 3 and metrics["failed_cases"] == 0
    assert metrics["hit_cases"] == 2 and metrics["no_hit_cases"] == 1
    assert metrics["recall_at_k"] == 1.0
    assert metrics["mrr"] == 1.0 and metrics["top1_accuracy"] == 1.0
    assert all(metrics["gates"].values())


def test_aggregate_retrieval_gate_fails_on_expected_missing():
    bad = _synthetic_result("retrieval-004")
    bad["status"] = "failed"
    bad["checks"][0]["ok"] = False  # 仅 expected_injected 失败
    metrics = aggregate_retrieval([_synthetic_result("retrieval-005"), bad])
    assert metrics["failed_cases"] == 1
    assert metrics["gates"]["expected_coverage_full"] is False
    assert metrics["gates"]["order_stable_full"] is True
    assert metrics["gates"]["sources_consistent_full"] is True


# ------------------------------------------------------------- CLI 端到端


def _run_cli(argv: list[str]) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = evals_run.main(argv)
    return code, buffer.getvalue()


def test_cli_retrieval_suite_passes_end_to_end():
    code, output = _run_cli(["--suite", "retrieval.v1", "--no-report"])
    assert code == 0
    assert "通过 18 / 18" in output
    assert "[FAIL]" not in output
    assert "硬门禁：期望 chunk 全覆盖 OK" in output
    assert "不与 B0 33 条混算" in output


def test_cli_retrieval_suite_case_and_category_filters():
    code, output = _run_cli(
        ["--suite", "retrieval.v1", "--case-id", "retrieval-017", "--no-report"]
    )
    assert code == 0
    assert "retrieval-017" in output and "retrieval-001" not in output

    code, output = _run_cli(["--suite", "retrieval.v1", "--category", "no-hit", "--no-report"])
    assert code == 0
    assert "通过 3 / 3" in output


def test_cli_retrieval_suite_rejects_live_and_unknown_category():
    code, output = _run_cli(["--suite", "retrieval.v1", "--live", "--no-report"])
    assert code == 2 and "不支持 --live" in output

    code, output = _run_cli(["--suite", "retrieval.v1", "--category", "nope", "--no-report"])
    assert code == 2 and "未知分类" in output


def test_cli_retrieval_report_written_to_out(tmp_path):
    out = tmp_path / "retrieval-report.json"
    code, output = _run_cli(["--suite", "retrieval.v1", "--out", str(out)])
    assert code == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["meta"]["report_schema_version"] == 1
    assert report["meta"]["suite"] == "retrieval.v1"
    assert report["meta"]["case_count"] == 18
    assert report["failures"] == []
    assert all(report["metrics"]["gates"].values())
