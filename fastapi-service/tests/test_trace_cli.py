"""Trace CLI 测试：树形时间线渲染、JSON 输出、退出码（2/3/4/5）、坏行容忍与默认路径解析。

Trace 文件由真实 JsonlTraceSink 写出一份，保证 CLI 与落盘格式兼容；其余用 tmp_path 手工构造。
"""

import asyncio
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from fastapi.testclient import TestClient

from app.agent.events import TRACE_VERSION
from app.main import create_app
from app.models.fake import FakeModelProvider
from app.rag.retriever import EmptyRetriever
from app.tools.schema_loader import ToolSchemaLoader
from app.trace_cli import DEFAULT_TRACE_PATH, main, render_timeline, resolve_trace_path
from tests.conftest import SNAPSHOT_PATH
from tests.test_trace_sink import DataClient, OneToolProvider, make as make_orchestrator

REQ_ID = "req-trace-cli-1"


def _write_real_trace(path: Path) -> None:
    asyncio.run(
        make_orchestrator(OneToolProvider(), DataClient(), path).chat(
            "AUD-001 库存？", request_id=REQ_ID
        )
    )


def _run_cli(argv: list[str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = main(argv)
    return code, out.getvalue(), err.getvalue()


def test_cli_renders_tree_timeline_from_real_trace(tmp_path):
    trace = tmp_path / "t.jsonl"
    _write_real_trace(trace)

    code, out, err = _run_cli(["--request-id", REQ_ID, "--input", str(trace)])

    assert code == 0 and err == ""
    assert f"request_id={REQ_ID}" in out
    assert "status=ok stop_reason=tool_assembled" in out
    assert "rounds_used=1/5 tool_calls=1 model_tools=3 schema_source=java" in out
    assert "events=5" in out
    assert "├─ schema_load" in out
    assert "├─ tool_call" in out
    assert "tool=query-stock-level ok=true" in out and "outcome=ok" in out
    assert "└─ response_assemble" in out
    assert "sources=" not in out  # 无命中运行不渲染 sources 行


def test_cli_json_output_matches_record(tmp_path):
    trace = tmp_path / "t.jsonl"
    _write_real_trace(trace)

    code, out, err = _run_cli(["--request-id", REQ_ID, "--input", str(trace), "--json"])

    assert code == 0 and err == ""
    record = json.loads(out)
    assert record["trace_version"] == TRACE_VERSION
    assert record["run"]["request_id"] == REQ_ID
    assert len(record["events"]) == 5


def test_cli_renders_last_duplicate_and_skips_bad_lines(tmp_path):
    trace = tmp_path / "t.jsonl"
    _write_real_trace(trace)
    content = trace.read_text(encoding="utf-8")
    trace.write_text("{broken line\n" + content + content, encoding="utf-8")

    code, out, err = _run_cli(["--request-id", REQ_ID, "--input", str(trace)])

    assert code == 0
    assert "找到 2 条同 request_id 记录" in err
    assert "跳过 1 行无法解析" in err
    assert out.count(f"request_id={REQ_ID} ") == 1


def test_cli_exit_codes_usage_missing_file_and_unknown_request(tmp_path):
    missing = tmp_path / "nope.jsonl"
    code, _, err = _run_cli(["--request-id", "x", "--input", str(missing)])
    assert code == 3 and "不存在" in err

    trace = tmp_path / "t.jsonl"
    _write_real_trace(trace)
    code, _, err = _run_cli(["--request-id", "req-unknown", "--input", str(trace)])
    assert code == 4 and "未找到" in err

    code, _, _ = _run_cli([])  # 缺 --request-id
    assert code == 2


def test_cli_format_errors_return_5(tmp_path):
    garbage = tmp_path / "garbage.jsonl"
    garbage.write_text("{not json\n", encoding="utf-8")
    code, _, err = _run_cli(["--request-id", "x", "--input", str(garbage)])
    assert code == 5 and "无法解析" in err

    old_version = tmp_path / "old.jsonl"
    old_version.write_text(
        json.dumps({"trace_version": 99, "run": {"request_id": "req-old"}, "events": []}) + "\n",
        encoding="utf-8",
    )
    code, _, err = _run_cli(["--request-id", "req-old", "--input", str(old_version)])
    assert code == 5 and "trace_version=99" in err

    broken = tmp_path / "broken.jsonl"
    broken.write_text(
        json.dumps(
            {"trace_version": TRACE_VERSION, "run": {"request_id": "req-broken"}, "events": []}
        )
        + "\n",
        encoding="utf-8",
    )
    code, _, err = _run_cli(["--request-id", "req-broken", "--input", str(broken)])
    assert code == 5 and "缺少字段" in err

    malformed_event = tmp_path / "malformed-event.jsonl"
    malformed_event.write_text(
        json.dumps(
            {
                "trace_version": TRACE_VERSION,
                "run": {
                    "request_id": "req-event-broken",
                    "conversation_id": "conv",
                    "started_at": "2026-10-04T00:00:00+00:00",
                    "duration_ms": 1,
                    "budget_seconds": 25,
                    "max_rounds": 5,
                    "rounds_used": 1,
                    "stop_reason": "answered",
                    "status": "ok",
                    "tool_calls": 0,
                    "model_tools": 3,
                    "schema_source": "java",
                    "sources": [],
                    "event_count": 1,
                },
                "events": ["not-an-object"],
            }
        )
        + "\n",
        encoding="utf-8",
    )
    code, _, err = _run_cli(
        ["--request-id", "req-event-broken", "--input", str(malformed_event)]
    )
    assert code == 5 and "events[0] 不是 JSON 对象" in err


def test_resolve_trace_path_precedence(monkeypatch):
    assert resolve_trace_path("/tmp/explicit.jsonl") == Path("/tmp/explicit.jsonl")

    monkeypatch.setenv("AGENT_TRACE_FILE", "/tmp/from-env.jsonl")
    assert resolve_trace_path(None) == Path("/tmp/from-env.jsonl")

    monkeypatch.delenv("AGENT_TRACE_FILE")
    assert resolve_trace_path(None) == DEFAULT_TRACE_PATH
    assert str(DEFAULT_TRACE_PATH).endswith(str(Path("logs") / "agent-trace.jsonl"))

    monkeypatch.setenv("AGENT_TRACE_FILE", "   ")
    assert resolve_trace_path(None) == DEFAULT_TRACE_PATH


def test_render_timeline_includes_sources_and_skips_none_fields():
    record = {
        "trace_version": TRACE_VERSION,
        "run": {
            "request_id": "req-r",
            "conversation_id": "conv-r",
            "started_at": "2026-10-04T00:00:00+00:00",
            "duration_ms": 12,
            "budget_seconds": 25.0,
            "max_rounds": 5,
            "rounds_used": 1,
            "stop_reason": "answered",
            "status": "ok",
            "tool_calls": 0,
            "model_tools": 3,
            "schema_source": "java",
            "sources": ["supply-ops.zh#stock-level-1"],
            "event_count": 1,
        },
        "events": [
            {
                "type": "rag_retrieve",
                "duration_ms": 3,
                "chunks": 1,
                "error_class": None,
                "hit_ids": ["a", "b"],
            }
        ],
    }

    text = render_timeline(record)

    assert 'sources=["supply-ops.zh#stock-level-1"]' in text
    assert '└─ rag_retrieve (3ms) chunks=1 hit_ids=["a", "b"]' in text
    assert "error_class" not in text  # None 字段不渲染


def test_trace_enabled_http_path_writes_file_the_cli_reads(
    tmp_path, java_client_factory, payloads
):
    """create_app(trace_file) → /agent/chat → JsonlTraceSink 落盘 → CLI 可读（接线回归）。"""
    trace = tmp_path / "logs" / "agent-trace.jsonl"
    client = java_client_factory(tool_payloads=payloads)
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    app = create_app(
        java_client=client,
        provider=FakeModelProvider(),
        schema_loader=loader,
        retriever=EmptyRetriever(),
        trace_file=trace,
    )
    with TestClient(app) as test_client:
        response = test_client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存还有多少？"},
            headers={
                "Authorization": "Bearer unit-viewer-token-123",
                "X-Request-Id": "req-trace-e2e",
            },
        )
    assert response.status_code == 200
    assert response.json()["data"]["request_id"] == "req-trace-e2e"
    assert trace.is_file()

    code, out, err = _run_cli(["--request-id", "req-trace-e2e", "--input", str(trace)])
    assert code == 0 and err == ""
    assert "request_id=req-trace-e2e" in out
    assert "status=ok" in out
    assert "├─ rag_retrieve" in out  # EmptyRetriever 零注入也有检索阶段事件
