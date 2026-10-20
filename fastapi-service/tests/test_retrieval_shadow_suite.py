"""retrieval.shadow 对照套件测试：资产校验（唯一/互斥/存在/数量）、两侧同批查询实跑、
并列聚合口径与 CLI 端到端。

全部走进程内静态语料与纯函数：无模型、无 Java、无网络、无 cassette。
"""

import copy
import io
import json
from contextlib import redirect_stdout

import pytest
from evals.support import FROZEN_KNOWLEDGE_DIR

from app.rag.retriever import KnowledgeRetriever
from app.rag.retriever_v2 import KnowledgeRetrieverV2
from evals import run as evals_run
from evals.metrics import (
    aggregate_retrieval_shadow,
    allowed_injection_rate,
    shadow_side_checks,
)
from evals.support import (
    RETRIEVAL_SHADOW_CASES_PATH,
    RETRIEVAL_SHADOW_TOTAL_CASES,
    load_retrieval_shadow_cases,
    validate_retrieval_shadow_cases,
)


@pytest.fixture(scope="module")
def v2_retriever() -> KnowledgeRetrieverV2:
    return KnowledgeRetrieverV2(FROZEN_KNOWLEDGE_DIR)


@pytest.fixture(scope="module")
def known_ids(v2_retriever) -> set[str]:
    return {chunk.chunk_id for chunk in v2_retriever.chunks}


def _cases() -> list[dict]:
    return load_retrieval_shadow_cases()


# ---------------------------------------------------------------- 资产与校验


def test_shadow_cases_load_validate_and_are_explicitly_named(known_ids):
    assert RETRIEVAL_SHADOW_CASES_PATH.exists()
    assert RETRIEVAL_SHADOW_CASES_PATH.name == "retrieval.shadow.v1.jsonl"
    cases = _cases()
    assert len(cases) == RETRIEVAL_SHADOW_TOTAL_CASES == 20
    assert [case["case_id"] for case in cases] == [
        f"retrieval-shadow-{index:03d}" for index in range(1, 21)
    ]
    assert all(case["notes"].strip() for case in cases)  # 人工依据说明逐条非空
    validate_retrieval_shadow_cases(cases, known_ids)


def _duplicate_id(cases):
    cases[1]["case_id"] = cases[0]["case_id"]


def _drop_one(cases):
    cases.pop()


def _unknown_chunk(cases):
    cases[0]["allowed_chunk_ids"].append("supply-ops.zh#nope-1")


def _no_hit_with_labels(cases):
    no_hit = next(case for case in cases if case["expect_no_hit"])
    no_hit["forbidden_chunk_ids"] = ["supply-ops.zh#stock-level-1"]


def _not_disjoint(cases):
    cases[14]["allowed_chunk_ids"].append(cases[14]["expected_chunk_ids"][0])


def _bad_naming(cases):
    cases[0]["case_id"] = "retrieval-v2-001"


@pytest.mark.parametrize(
    ("mutator", "keyword"),
    [
        (_duplicate_id, "重复"),
        (_drop_one, "用例数必须为"),
        (_unknown_chunk, "不在语料索引"),
        (_no_hit_with_labels, "必须全空"),
        (_not_disjoint, "互斥"),
        (_bad_naming, "命名"),
    ],
)
def test_shadow_validation_rejects_broken_cases(known_ids, mutator, keyword):
    cases = copy.deepcopy(_cases())
    mutator(cases)
    with pytest.raises(ValueError) as excinfo:
        validate_retrieval_shadow_cases(cases, known_ids)
    assert keyword in str(excinfo.value)


# ------------------------------------------------------------ 口径与聚合纯函数


def test_allowed_injection_rate_units_and_caliber():
    assert allowed_injection_rate([], ["a"], ["b"]) is None
    assert allowed_injection_rate(["a", "b"], ["a"], []) == 0.5
    assert allowed_injection_rate(["a", "b"], ["a"], ["b"]) == 1.0
    assert allowed_injection_rate(["c"], ["a"], []) == 0.0


def test_shadow_side_checks_flag_non_deterministic_output():
    observation = {
        "hit_ids": ["a"],
        "hit_scores": [0.5],
        "injected_ids": ["a"],
        "injected_budget_used": 10,
        "second_pass_ids": ["b"],
        "second_pass_scores": [0.5],
        "source_ids": ["a"],
    }
    checks = {check["name"]: check for check in shadow_side_checks(observation)}
    assert checks["executed"]["ok"] is False
    assert checks["order_stable"]["ok"] is False
    assert checks["sources_consistency"]["ok"] is True
    assert checks["top_k"]["ok"] is True


def test_shadow_side_checks_reject_degraded_retriever():
    observation = {
        "retriever_status": "missing_directory",
        "hit_ids": [],
        "hit_scores": [],
        "injected_ids": [],
        "second_pass_ids": [],
        "second_pass_scores": [],
        "source_ids": [],
        "injected_budget_used": 0,
    }
    checks = {check["name"]: check for check in shadow_side_checks(observation)}
    assert checks["executed"]["ok"] is False


def _synthetic_side(
    *,
    no_hit: bool = False,
    recall: float = 1.0,
    expected_hit: int = 1,
    expected_total: int = 1,
    injected: tuple[str, ...] = ("supply-ops.zh#a-1",),
    forbidden: tuple[str, ...] = (),
    rate: float = 1.0,
) -> dict:
    return {
        "hit_ids": list(injected) if not no_hit else [],
        "injected_ids": list(injected),
        "injected_budget_used": 100,
        "expected_total": expected_total,
        "expected_hit_count": expected_hit,
        "recall_at_k": None if no_hit else recall,
        "reciprocal_rank": 1.0,
        "top1_hit": bool(injected) and not no_hit,
        "forbidden_injected": list(forbidden),
        "allowed_injection_rate": None if no_hit else rate,
        "extra_chunks": len(injected) - expected_hit,
    }


def _synthetic_shadow_result(case_id: str, *, no_hit=False, status="passed", **side_kwargs) -> dict:
    side = _synthetic_side(no_hit=no_hit, **side_kwargs)
    return {
        "case_id": case_id,
        "category": "no-hit" if no_hit else "colloquial",
        "title": "合成",
        "expect_no_hit": no_hit,
        "status": status,
        "duration_ms": 0,
        "checks": [],
        "sides": {"v1": dict(side), "v2": dict(side)},
    }


def test_aggregate_shadow_reports_side_by_side_metrics():
    results = [
        _synthetic_shadow_result("retrieval-shadow-001"),
        _synthetic_shadow_result(
            "retrieval-shadow-002",
            recall=0.5,
            expected_hit=1,
            expected_total=2,
            injected=("supply-ops.zh#a-1", "supply-ops.zh#z-1"),
            forbidden=("supply-ops.zh#z-1",),
            rate=0.5,
        ),
        _synthetic_shadow_result(
            "retrieval-shadow-017", no_hit=True, injected=(), expected_hit=0, expected_total=0
        ),
    ]
    metrics = aggregate_retrieval_shadow(results)
    assert metrics["total_cases"] == 3
    assert metrics["failed_cases"] == 0
    assert metrics["gates"]["comparison_integrity_full"] is True
    for side in ("v1", "v2"):
        m = metrics[side]
        assert m["hit_cases"] == 2 and m["no_hit_cases"] == 1
        assert m["expected_total"] == 3 and m["expected_hit_total"] == 2
        assert m["recall_micro"] == round(2 / 3, 4)
        assert m["expected_covered_cases"] == 1
        assert m["top1_accuracy"] == 1.0 and m["mrr"] == 1.0
        assert m["forbidden_injected_total"] == 1
        assert m["no_hit_clean_cases"] == 1
        assert m["injected_total"] == 3
        assert m["extra_chunks_total"] == 1
        assert m["allowed_injection_rate"] == 0.75


def test_aggregate_shadow_gate_fails_on_broken_comparison():
    bad = _synthetic_shadow_result("retrieval-shadow-003", status="failed")
    metrics = aggregate_retrieval_shadow(
        [_synthetic_shadow_result("retrieval-shadow-004"), bad]
    )
    assert metrics["failed_cases"] == 1
    assert metrics["gates"]["comparison_integrity_full"] is False


# ------------------------------------------------------- 两侧同批查询实跑（核心）


def _run_cli(argv: list[str]) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = evals_run.main(argv)
    return code, buffer.getvalue()


def test_shadow_suite_runs_same_queries_on_both_retrievers(monkeypatch):
    """两侧实跑证明：同一 shadow 数据、相同 query 序列（每条双跑，供确定性检查）。"""
    calls = {"v1": [], "v2": []}

    class RecordingV1(KnowledgeRetriever):
        def search(self, query, request_id=""):
            calls["v1"].append(query)
            return super().search(query, request_id=request_id)

    class RecordingV2(KnowledgeRetrieverV2):
        def search(self, query, request_id=""):
            calls["v2"].append(query)
            return super().search(query, request_id=request_id)

    monkeypatch.setattr(evals_run, "KnowledgeRetriever", RecordingV1)
    monkeypatch.setattr(evals_run, "KnowledgeRetrieverV2", RecordingV2)

    code, output = _run_cli(["--suite", "retrieval.shadow", "--no-report"])
    assert code == 0

    expected_queries = [case["query"].strip() for case in _cases()]
    assert calls["v1"] == calls["v2"]  # 两侧 query 序列一致
    assert calls["v1"] == [query for query in expected_queries for _ in range(2)]


# ------------------------------------------------------------- CLI 端到端


def test_cli_shadow_suite_passes_end_to_end():
    code, output = _run_cli(["--suite", "retrieval.shadow", "--no-report"])
    assert code == 0
    assert "对照完成 20 / 20" in output
    assert "[FAIL]" not in output
    assert "v1（冻结对照，break 预算）" in output
    assert "v2（生产默认，skip-continue 预算）" in output
    assert "受控词法回归对照" in output
    assert "非标准 Precision" in output
    assert "硬门禁：对照完整性" in output and "OK" in output


def test_cli_shadow_suite_case_and_category_filters():
    code, output = _run_cli(
        ["--suite", "retrieval.shadow", "--case-id", "retrieval-shadow-011", "--no-report"]
    )
    assert code == 0
    assert "retrieval-shadow-011" in output and "retrieval-shadow-001" not in output

    code, output = _run_cli(
        ["--suite", "retrieval.shadow", "--category", "no-hit", "--no-report"]
    )
    assert code == 0
    assert "对照完成 2 / 2" in output


def test_cli_shadow_suite_rejects_live_and_unknown_category():
    code, output = _run_cli(["--suite", "retrieval.shadow", "--live", "--no-report"])
    assert code == 2 and "不支持 --live" in output

    code, output = _run_cli(
        ["--suite", "retrieval.shadow", "--category", "nope", "--no-report"]
    )
    assert code == 2 and "未知分类" in output


def test_cli_shadow_report_written_to_out(tmp_path):
    out = tmp_path / "retrieval-shadow-report.json"
    code, output = _run_cli(["--suite", "retrieval.shadow", "--out", str(out)])
    assert code == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["meta"]["report_schema_version"] == 3
    assert report["meta"]["suite"] == "retrieval.shadow"
    assert report["meta"]["case_count"] == 20
    assert report["meta"]["retriever_v1_status"] == "ok"
    assert report["meta"]["retriever_v2_status"] == "ok"
    assert report["meta"]["cases_file"].endswith("retrieval.shadow.v1.jsonl")
    assert report["failures"] == []
    assert report["metrics"]["gates"]["comparison_integrity_full"] is True
    assert report["metrics"]["v1"]["hit_cases"] == 16
    assert report["metrics"]["v2"]["expected_hit_total"] == 17
