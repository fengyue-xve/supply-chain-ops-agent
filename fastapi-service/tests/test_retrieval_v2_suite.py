"""retrieval.v2 检索层评测测试：用例集校验（互斥+全语料覆盖）、逐条判定、聚合门禁与 CLI 端到端。

全部走进程内静态语料与纯函数：无模型、无 Java、无网络、无 cassette。
"""

import copy
import io
import json
from contextlib import redirect_stdout

import pytest
from evals.support import FROZEN_KNOWLEDGE_DIR

from app.rag.retriever_v2 import KnowledgeRetrieverV2
from evals import run as evals_run
from evals.metrics import (
    aggregate_retrieval_v2,
    evaluate_retrieval_v2_case,
    injection_relevance,
)
from evals.support import (
    RETRIEVAL_V2_CASES_PATH,
    RETRIEVAL_V2_TOTAL_CASES,
    load_retrieval_v2_cases,
    validate_retrieval_v2_cases,
)


@pytest.fixture(scope="module")
def retriever() -> KnowledgeRetrieverV2:
    return KnowledgeRetrieverV2(FROZEN_KNOWLEDGE_DIR)


@pytest.fixture(scope="module")
def known_ids(retriever) -> set[str]:
    return {chunk.chunk_id for chunk in retriever.chunks}


def _cases() -> list[dict]:
    return load_retrieval_v2_cases()


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


def _hit_case(*, expected=("supply-ops.zh#a-1",), allowed=(), forbidden=("supply-ops.zh#z-1",)) -> dict:
    return {
        "case_id": "retrieval-v2-099",
        "category": "colloquial",
        "title": "合成用例",
        "query": "q",
        "expected_chunk_ids": list(expected),
        "allowed_chunk_ids": list(allowed),
        "forbidden_chunk_ids": list(forbidden),
        "expect_no_hit": False,
        "notes": "合成标签",
    }


def _checks_by_name(case: dict, observation: dict) -> dict[str, dict]:
    return {check["name"]: check for check in evaluate_retrieval_v2_case(case, observation)}


# ---------------------------------------------------------------- 用例集与校验


def test_cases_load_and_validate(known_ids):
    assert RETRIEVAL_V2_CASES_PATH.exists()
    cases = _cases()
    assert len(cases) == RETRIEVAL_V2_TOTAL_CASES == 20
    assert [case["case_id"] for case in cases] == [
        f"retrieval-v2-{index:03d}" for index in range(1, 21)
    ]
    assert {case["category"] for case in cases} == {
        "colloquial",
        "mixed",
        "short",
        "sku-value",
        "combo",
        "no-hit",
        "near",
    }
    validate_retrieval_v2_cases(cases, known_ids)


def _drop_one(cases):
    cases.pop()


def _unknown_chunk(cases):
    cases[0]["allowed_chunk_ids"].append("supply-ops.zh#nope-1")


def _no_hit_with_expected(cases):
    no_hit = next(case for case in cases if case["expect_no_hit"])
    no_hit["expected_chunk_ids"] = ["supply-ops.zh#stock-level-1"]


def _missing_field(cases):
    del cases[0]["notes"]


def _empty_notes(cases):
    cases[0]["notes"] = "  "


def _bad_category(cases):
    cases[0]["category"] = "other"


def _not_disjoint(cases):
    # 同一 chunk 同时出现在 expected 与 allowed → 必须命中「互斥」校验
    cases[14]["allowed_chunk_ids"].append(cases[14]["expected_chunk_ids"][0])


def _incomplete_coverage(cases):
    cases[0]["forbidden_chunk_ids"].pop()


@pytest.mark.parametrize(
    ("mutator", "keyword"),
    [
        (_drop_one, "用例数必须为"),
        (_unknown_chunk, "不在语料索引"),
        (_no_hit_with_expected, "必须全空"),
        (_missing_field, "缺少字段"),
        (_empty_notes, "notes 为空"),
        (_bad_category, "category 非法"),
        (_not_disjoint, "互斥"),
        (_incomplete_coverage, "未覆盖全部语料"),
    ],
)
def test_validation_rejects_broken_cases(known_ids, mutator, keyword):
    cases = copy.deepcopy(_cases())
    mutator(cases)
    with pytest.raises(ValueError) as excinfo:
        validate_retrieval_v2_cases(cases, known_ids)
    assert keyword in str(excinfo.value)


# ------------------------------------------------------------------ 逐条判定


def test_all_checks_pass_on_consistent_observation():
    checks = evaluate_retrieval_v2_case(_hit_case(), _observation())
    assert {check["name"] for check in checks} == {
        "expected_injected",
        "forbidden_injected",
        "top_k",
        "budget",
        "threshold",
        "order_stable",
        "sources_consistency",
    }
    assert all(check["ok"] is True for check in checks)


def test_missing_expected_and_injected_forbidden_are_flagged():
    checks = _checks_by_name(
        _hit_case(expected=("supply-ops.zh#a-1", "supply-ops.zh#b-1")),
        _observation(injected_ids=["supply-ops.zh#a-1"], source_ids=["supply-ops.zh#a-1"]),
    )
    assert checks["expected_injected"]["ok"] is False
    assert checks["forbidden_injected"]["ok"] is True

    checks = _checks_by_name(
        _hit_case(),
        _observation(
            injected_ids=["supply-ops.zh#a-1", "supply-ops.zh#z-1"],
            second_pass_ids=["supply-ops.zh#a-1"],
            source_ids=["supply-ops.zh#a-1", "supply-ops.zh#z-1"],
        ),
    )
    assert checks["forbidden_injected"]["ok"] is False
    assert "supply-ops.zh#z-1" in checks["forbidden_injected"]["detail"]
    assert checks["sources_consistency"]["ok"] is True


def test_no_hit_case_requires_zero_hits_and_rejects_any():
    case = {**_hit_case(expected=(), forbidden=()), "expect_no_hit": True}
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


def test_injection_relevance_units():
    assert injection_relevance([], ["a"], ["b"]) is None
    assert injection_relevance(["a", "b"], ["a"], []) == 0.5
    assert injection_relevance(["a", "b"], ["a"], ["b"]) == 1.0
    assert injection_relevance(["c"], ["a"], []) == 0.0


def _synthetic_result(case_id: str, *, no_hit: bool = False, ok: bool = True) -> dict:
    names = (["no_hit"] if no_hit else ["expected_injected", "forbidden_injected"]) + [
        "top_k",
        "budget",
        "threshold",
        "order_stable",
        "sources_consistency",
    ]
    return {
        "case_id": case_id,
        "category": "no-hit" if no_hit else "colloquial",
        "title": "合成",
        "status": "passed" if ok else "failed",
        "duration_ms": 0,
        "checks": [{"name": name, "ok": ok, "detail": ""} for name in names],
        "summary": {
            "expect_no_hit": no_hit,
            "expected_chunk_ids": [],
            "allowed_chunk_ids": [],
            "forbidden_chunk_ids": [],
            "hit_ids": [],
            "injected_ids": [] if no_hit else ["supply-ops.zh#a-1"],
            "injected_budget_used": 0,
            "retriever_status": "ok",
            "corpus_chunks": 12,
            "recall_at_k": None if no_hit else 1.0,
            "reciprocal_rank": 1.0,
            "top1_hit": ok,
            "precision_at_k": None if no_hit else 1.0,
            "expected_hit_count": 0 if no_hit else 1,
            "forbidden_injected": [],
        },
    }


def test_aggregate_v2_metrics_and_gates():
    results = [
        _synthetic_result("retrieval-v2-001"),
        _synthetic_result("retrieval-v2-002"),
        _synthetic_result("retrieval-v2-017", no_hit=True),
    ]
    metrics = aggregate_retrieval_v2(results)
    assert metrics["total_cases"] == 3
    assert metrics["passed_cases"] == 3 and metrics["failed_cases"] == 0
    assert metrics["hit_cases"] == 2 and metrics["no_hit_cases"] == 1
    assert metrics["recall_at_k"] == 1.0
    assert metrics["mrr"] == 1.0 and metrics["top1_accuracy"] == 1.0
    assert metrics["precision_at_k"] == 1.0
    assert metrics["noise_injected_total"] == 0
    assert all(metrics["gates"].values())


def test_aggregate_v2_gate_fails_on_forbidden_injection():
    bad = _synthetic_result("retrieval-v2-004")
    bad["status"] = "failed"
    forbidden_check = next(
        check for check in bad["checks"] if check["name"] == "forbidden_injected"
    )
    forbidden_check["ok"] = False
    bad["summary"]["injected_ids"] = ["supply-ops.zh#z-1"]
    bad["summary"]["forbidden_injected"] = ["supply-ops.zh#z-1"]
    bad["summary"]["precision_at_k"] = 0.0

    metrics = aggregate_retrieval_v2([_synthetic_result("retrieval-v2-005"), bad])
    assert metrics["failed_cases"] == 1
    assert metrics["gates"]["forbidden_absent_full"] is False
    assert metrics["gates"]["expected_coverage_full"] is True
    assert metrics["noise_injected_total"] == 1
    assert metrics["precision_at_k"] == 0.5


# ------------------------------------------------------------- CLI 端到端


def _run_cli(argv: list[str]) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = evals_run.main(argv)
    return code, buffer.getvalue()


def test_cli_v2_suite_passes_end_to_end():
    code, output = _run_cli(["--suite", "retrieval.v2", "--no-report"])
    assert code == 0
    assert "通过 20 / 20" in output
    assert "[FAIL]" not in output
    assert "硬门禁：期望 chunk 全覆盖 OK" in output
    assert "forbidden 零注入 OK" in output
    assert "不构成语义检索质量或模型质量结论" in output


def test_cli_v2_suite_case_and_category_filters():
    code, output = _run_cli(
        ["--suite", "retrieval.v2", "--case-id", "retrieval-v2-011", "--no-report"]
    )
    assert code == 0
    assert "retrieval-v2-011" in output and "retrieval-v2-001" not in output

    code, output = _run_cli(["--suite", "retrieval.v2", "--category", "no-hit", "--no-report"])
    assert code == 0
    assert "通过 2 / 2" in output

    code, output = _run_cli(["--suite", "retrieval.v2", "--category", "near", "--no-report"])
    assert code == 0
    assert "通过 2 / 2" in output


def test_cli_v2_suite_rejects_live_and_unknown_category():
    code, output = _run_cli(["--suite", "retrieval.v2", "--live", "--no-report"])
    assert code == 2 and "不支持 --live" in output

    code, output = _run_cli(["--suite", "retrieval.v2", "--category", "nope", "--no-report"])
    assert code == 2 and "未知分类" in output


def test_cli_v2_report_written_to_out(tmp_path):
    out = tmp_path / "retrieval-v2-report.json"
    code, output = _run_cli(["--suite", "retrieval.v2", "--out", str(out)])
    assert code == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["meta"]["report_schema_version"] == 2
    assert report["meta"]["suite"] == "retrieval.v2"
    assert report["meta"]["case_count"] == 20
    assert report["meta"]["retriever_status"] == "ok"
    assert report["failures"] == []
    assert all(report["metrics"]["gates"].values())


def test_retrieval_v1_suite_still_uses_v1_retriever_and_passes():
    """冻结护栏：v2 接入不得改变 retrieval.v1 的行为与结果。"""
    code, output = _run_cli(["--suite", "retrieval.v1", "--no-report"])
    assert code == 0
    assert "通过 18 / 18" in output
    assert "不与 B0 33 条混算" in output
