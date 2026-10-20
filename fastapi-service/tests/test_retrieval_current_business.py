"""Business-authored current-corpus checks; known noise remains a failed CLI result.

These tests verify audit integrity and an explicit no-regression floor, not model quality.
"""
import copy
import io
import json
from contextlib import redirect_stdout

import pytest

from app.rag.documents import DEFAULT_KNOWLEDGE_DIR
from app.rag.retriever_v3 import KnowledgeRetrieverV3, normalize_query
from evals import current_business as current
from evals.run import main, _run_retrieval_v2_case
from evals.metrics import aggregate_retrieval_v2
from evals.support import FROZEN_KNOWLEDGE_DIR


def cli(args):
    output = io.StringIO()
    with redirect_stdout(output):
        code = main(["--suite", current.SUITE, *args])
    return code, output.getvalue()


@pytest.fixture
def retriever():
    return KnowledgeRetrieverV3(DEFAULT_KNOWLEDGE_DIR)


def test_current_labels_cover_business_sections_and_preserve_frozen_corpus(retriever):
    cases = current.load_cases()
    current.validate_cases(cases, {c.chunk_id for c in retriever.chunks})
    assert len(cases) == 30
    assert {c["category"] for c in cases} == current.CATEGORIES
    assert sum(c["expect_no_hit"] for c in cases) == 5
    assert DEFAULT_KNOWLEDGE_DIR.resolve() != FROZEN_KNOWLEDGE_DIR.resolve()
    historical = (FROZEN_KNOWLEDGE_DIR / "supply-ops.zh.md").read_text(encoding="utf8")
    assert "安全库存是固定值 20" in historical
    assert current.corpus_manifest(DEFAULT_KNOWLEDGE_DIR) != current.corpus_manifest(FROZEN_KNOWLEDGE_DIR)


@pytest.mark.parametrize("mutation,expected", [
    (lambda cs: cs.pop(), "30"),
    (lambda cs: cs[0].update(case_id=cs[1]["case_id"]), "唯一"),
    (lambda cs: cs[0].update(notes=""), "notes"),
    (lambda cs: cs[0]["allowed_chunk_ids"].append(cs[0]["expected_chunk_ids"][0]), "互斥"),
    (lambda cs: cs[0]["forbidden_chunk_ids"].pop(), "全部"),
    (lambda cs: cs[0]["allowed_chunk_ids"].append("unknown#x-1"), "不在当前"),
    (lambda cs: cs[-1]["allowed_chunk_ids"].append("supply-ops.zh#stock-level-1"), "全空"),
])
def test_invalid_or_weakened_labels_are_rejected(retriever, mutation, expected):
    cases = copy.deepcopy(current.load_cases())
    mutation(cases)
    with pytest.raises(ValueError, match=expected):
        current.validate_cases(cases, {c.chunk_id for c in retriever.chunks})


def test_business_expected_coverage_and_negative_clean_floor(retriever):
    results = [_run_retrieval_v2_case(c, retriever) for c in current.load_cases()]
    metrics = aggregate_retrieval_v2(results)
    assert metrics["expected_covered_cases"] == metrics["hit_cases"] == 25
    assert metrics["no_hit_clean_cases"] == metrics["no_hit_cases"] == 5
    # Strict labels remain unchanged while the new retriever removes shared-word noise.
    failed = {r["case_id"] for r in results if r["status"] == "failed"}
    assert failed == set()
    assert metrics["passed_cases"] == 30
    for r in results:
        if r["status"] == "failed":
            assert {c["name"] for c in r["checks"] if not c["ok"]} == {"forbidden_injected"}
    assert metrics["budget_respected_rate"] == metrics["sources_consistency_rate"] == 1.0


def test_default_value_alias_is_longest_first_and_recalls_parameter_section(retriever):
    assert normalize_query("默认值") == normalize_query("默认") == "缺省"
    hits = retriever.select_for_prompt(retriever.search("targetCoverDays可以填多少，默认值是多少？"))
    assert "replenish-params" in {c.section_id for c in hits}


def test_cli_known_noise_is_failure_not_greenwashed_report(tmp_path):
    path = tmp_path / "current.json"
    code, output = cli(["--out", str(path)])
    report = json.loads(path.read_text(encoding="utf8"))
    assert code == (1 if report["failures"] else 0)
    assert "不构成模型回答质量" in output
    assert "历史" not in output.splitlines()[0]
    assert report["meta"]["corpus_kind"] == "current-application"
    assert report["meta"]["model"] is None
    assert report["meta"]["case_count"] == 30
    assert report["meta"]["corpus_manifest"] == current.corpus_manifest(DEFAULT_KNOWLEDGE_DIR)
    assert len(report["meta"]["cases_sha256"]) == 64
    assert "allowed_injection_rate_mean" in report["metrics"]
    assert "precision_at_k" not in report["metrics"]
    for row in report["historical_labels_on_current_corpus_audit"]:
        assert row["classification"] in {"valid-adjacency", "lexical-noise"}


def test_cli_filters_can_run_clean_subset():
    code, output = cli(["--category", "negative", "--no-report"])
    assert code == 0 and "通过 5 / 5" in output
    code, output = cli(["--case-id", "current-business-012", "--no-report"])
    assert code == 0 and "通过 1 / 1" in output


@pytest.mark.parametrize("args", [
    ["--live"], ["--category", "unknown"], ["--case-id", "retrieval-v2-001"],
    ["--category", "negative", "--case-id", "current-business-001"],
])
def test_cli_rejects_live_and_invalid_selection(args):
    code, output = cli([*args, "--no-report"])
    assert code == 2 and "配置错误" in output


def test_cli_missing_current_corpus_does_not_silently_use_frozen(monkeypatch, tmp_path):
    monkeypatch.setenv("KNOWLEDGE_DIR", str(tmp_path / "missing"))
    code, output = cli(["--no-report"])
    assert code == 2 and "配置错误" in output
