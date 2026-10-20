"""Trace/CLI 运行治理测试：request_id 跨层校验与日志防伪、写盘字段白名单、
取消（cancelled）状态、同 request_id 并发隔离、CLI 流式读取与取消记录可读。

全部离线：注入 mock Java/模型、tmp_path 落盘，无网络、无真实服务、无真实模型。
"""

import asyncio
import io
import json
import logging
import re
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from app.agent.events import AgentEvent, JsonlTraceSink, StageLogSink
from app.agent.orchestrator import AgentOrchestrator
from app.agent.run import RunContext
from app.logging_middleware import (
    MAX_REQUEST_ID_LENGTH,
    RequestIdMiddleware,
    is_valid_request_id,
)
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.tools.schema_loader import ToolSchemaLoader
from app.trace_cli import main as trace_cli_main
from tests.conftest import SNAPSHOT_PATH
from tests.test_trace_sink import (
    DataClient,
    OneToolProvider,
    SchemaClient,
    UnknownToolProvider,
    make,
    read_records,
)

LOGGER_NAME = "app.agent.orchestrator"


# ------------------------------------------------ request_id 跨层校验（HTTP 面）


@pytest.fixture()
def echo_client() -> TestClient:
    app = FastAPI()

    @app.get("/echo")
    async def echo(request: Request) -> dict:
        return {"request_id": request.scope.get("request_id")}

    app.add_middleware(RequestIdMiddleware)
    return TestClient(app)


def test_valid_request_id_passes_through_byte_identical(echo_client):
    for request_id in ("req-Trace_e2e.1", "a" * MAX_REQUEST_ID_LENGTH, "0f8fad5b-d9cb" ):
        response = echo_client.get("/echo", headers={"X-Request-Id": request_id})
        assert response.status_code == 200
        assert response.json()["request_id"] == request_id
        assert response.headers["x-request-id"] == request_id


def test_missing_or_blank_request_id_generates_new_one(echo_client):
    response = echo_client.get("/echo")
    generated = response.json()["request_id"]
    assert response.status_code == 200
    assert generated.startswith("req-")
    assert response.headers["x-request-id"] == generated


def test_overlong_request_id_rejected_without_echo(echo_client):
    response = echo_client.get(
        "/echo", headers={"X-Request-Id": "a" * (MAX_REQUEST_ID_LENGTH + 1)}
    )
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == 400 and body["data"] is None
    assert "64" in body["message"]
    assert "x-request-id" not in response.headers  # 非法 id 不回写（与 Java 语义一致）


def test_injected_whitespace_request_id_rejected(echo_client):
    response = echo_client.get("/echo", headers={"X-Request-Id": "req id stage=forged"})
    assert response.status_code == 400
    assert response.json()["code"] == 400


def test_is_valid_request_id_charset_units():
    assert is_valid_request_id("req-1") is True
    assert is_valid_request_id("a" * 64) is True
    assert is_valid_request_id("a" * 65) is False
    assert is_valid_request_id("") is False
    assert is_valid_request_id("req id") is False  # 空格：日志行字段伪造
    assert is_valid_request_id("req\tid") is False
    assert is_valid_request_id("req\nid") is False
    assert is_valid_request_id("req\x00id") is False
    assert is_valid_request_id("req\x1b[31mid") is False
    assert is_valid_request_id("请求-id") is False


def test_stage_log_escapes_unsafe_request_id(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    sink = StageLogSink()
    sink.emit(AgentEvent(type="rag_retrieve", request_id="bad id\nfake", duration_ms=1))

    messages = [
        record.getMessage()
        for record in caplog.records
        if record.name == LOGGER_NAME
    ]
    assert messages == [
        "agent_stage stage=rag_retrieve request_id=bad\\x20id\\x0afake duration_ms=1"
    ]  # 空白/换行转义，不产生伪造日志行


# ------------------------------------------------------------ 写盘字段白名单


ALLOWED_EVENT_KEYS = frozenset(
    {
        "type",
        "duration_ms",
        "source",
        "error_class",
        "chunks",
        "hit_ids",
        "hit_scores",
        "injected_ids",
        "corpus_chunks",
        "retriever_status",
        "round",
        "tool_names",
        "tool",
        "ok",
        "param_keys",
        "outcome",
        "reason",
        "tool_calls",
        "reply_chars",
    }
)

RUN_SUMMARY_KEYS = (
    "request_id",
    "run_key",
    "conversation_id",
    "started_at",
    "duration_ms",
    "budget_seconds",
    "max_rounds",
    "rounds_used",
    "stop_reason",
    "status",
    "tool_calls",
    "model_tools",
    "schema_source",
    "sources",
    "event_count",
)


def test_trace_event_and_summary_fields_are_whitelisted(tmp_path):
    """正向白名单：新增字段必须同步更新本测试（即经人工确认的字段清单）。"""
    paths = []
    for provider, name in (
        (OneToolProvider(), "run.jsonl"),
        (UnknownToolProvider(), "blocked.jsonl"),
    ):
        path = tmp_path / name
        asyncio.run(make(provider, DataClient(), path).chat("库存？", request_id="req-wl"))
        paths.append(path)

    for path in paths:
        for record in read_records(path):
            assert tuple(record["run"].keys()) == RUN_SUMMARY_KEYS
            for event in record["events"]:
                assert set(event) <= ALLOWED_EVENT_KEYS, event


def test_run_summary_values_are_sanitized(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    sink = JsonlTraceSink(trace_path)
    run = RunContext(
        request_id="x" * 500,
        conversation_id="conv-safe",
        started_monotonic=0.0,
        deadline=1.0,
        total_budget_seconds=1.0,
        max_rounds=1,
        started_wall="2026-10-04T00:00:00+00:00",
    )
    sink.begin(run)
    sink.end(run)

    record = read_records(trace_path)[0]
    assert record["run"]["request_id"] == "x" * 200 + "<truncated>"


def test_trace_values_escape_control_characters(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    sink = JsonlTraceSink(trace_path)
    run = RunContext(
        request_id="req\nforged",
        conversation_id="conv\tvalue",
        started_monotonic=0.0,
        deadline=1.0,
        total_budget_seconds=1.0,
        max_rounds=1,
        started_wall="2026-10-04T00:00:00+00:00",
    )
    sink.begin(run)
    sink.emit(
        AgentEvent(
            type="tool_call",
            request_id=run.request_id,
            fields={"tool": "query\nstock", "round": 1},
            run_key=run.run_key,
        )
    )
    sink.end(run)

    record = read_records(trace_path)[0]
    assert record["run"]["request_id"] == r"req\x0aforged"
    assert record["run"]["conversation_id"] == r"conv\x09value"
    assert record["events"][0]["tool"] == r"query\x0astock"


# -------------------------------------------------------- 取消状态与并发隔离


class BlockingProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        await asyncio.Event().wait()  # 永不完成：等待外部取消


class SlowOneToolProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        await asyncio.sleep(0.02)
        return ModelResponse(
            tool_calls=[
                ToolCallRequest(
                    name="query-stock-level", params={"skus": ["AUD-001"]}, call_id="c1"
                )
            ]
        )


def _run_cli(argv: list[str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = trace_cli_main(argv)
    return code, out.getvalue(), err.getvalue()


def test_task_cancellation_records_cancelled_status(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    orchestrator = make(BlockingProvider(), DataClient(), trace_path)

    async def scenario():
        task = asyncio.create_task(
            orchestrator.chat("库存？", request_id="req-cancel-1")
        )
        await asyncio.sleep(0.05)  # 让任务推进到模型等待
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())

    run = read_records(trace_path)[0]["run"]
    assert run["stop_reason"] == "cancelled"
    assert run["status"] == "cancelled"

    # 取消记录仍可被 CLI 读取（状态矩阵在渲染层完整）
    code, out, err = _run_cli(["--request-id", "req-cancel-1", "--input", str(trace_path)])
    assert code == 0 and err == ""
    assert "stop_reason=cancelled" in out and "status=cancelled" in out


def test_concurrent_same_request_id_runs_stay_separate(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    sink = JsonlTraceSink(trace_path)
    loader = ToolSchemaLoader(SchemaClient(), snapshot_path=SNAPSHOT_PATH)
    orchestrator = AgentOrchestrator(
        SlowOneToolProvider(), DataClient(), loader, event_sink=sink
    )

    async def scenario():
        return await asyncio.gather(
            orchestrator.chat("库存？", request_id="req-dup-1"),
            orchestrator.chat("销量？", request_id="req-dup-1"),
            orchestrator.chat("档位？", request_id="req-dup-1"),
        )

    results = asyncio.run(scenario())

    assert all(result.request_id == "req-dup-1" for result in results)
    assert len({result.conversation_id for result in results}) == 3
    records = read_records(trace_path)
    assert len(records) == 3
    assert all(record["run"]["request_id"] == "req-dup-1" for record in records)
    assert all(record["run"]["status"] == "ok" for record in records)
    assert all(len(record["events"]) == 5 for record in records)
    assert all(record["run"]["event_count"] == 5 for record in records)
    assert sink._runs == {}  # 运行结束后无残留桶（run_key 隔离不泄漏）

    # run_key 落盘且两两不同；内部随机标识（uuid4 hex），不来自用户输入
    run_keys = [record["run"]["run_key"] for record in records]
    assert len(set(run_keys)) == 3
    assert all(re.fullmatch(r"[0-9a-f]{32}", key) for key in run_keys)
    assert all(key != "req-dup-1" for key in run_keys)

    # 精确指定其中一个 run_key：CLI 只渲染该条（无多条提示）
    target = records[1]
    code, out, err = _run_cli(
        [
            "--request-id",
            "req-dup-1",
            "--run-key",
            target["run"]["run_key"],
            "--input",
            str(trace_path),
        ]
    )
    assert code == 0 and err == ""
    assert out.count("request_id=req-dup-1 ") == 1
    assert f"run_key={target['run']['run_key']}" in out
    assert f"conversation_id={target['run']['conversation_id']}" in out
    for record in records:
        if record is not target:
            assert f"conversation_id={record['run']['conversation_id']}" not in out

    # 只给 request_id：行为与旧版一致（提示 + 渲染最后一条）
    code, out, err = _run_cli(["--request-id", "req-dup-1", "--input", str(trace_path)])
    assert code == 0
    assert "找到 3 条同 request_id 记录" in err
    assert out.count("request_id=req-dup-1 ") == 1
    assert f"conversation_id={records[-1]['run']['conversation_id']}" in out


def test_cli_run_key_mismatch_returns_not_found_exit_code(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "库存？", request_id="req-rk-1"
        )
    )

    code, _, err = _run_cli(
        ["--request-id", "req-rk-1", "--run-key", "0" * 32, "--input", str(trace_path)]
    )
    assert code == 4 and "未找到" in err and "0" * 32 in err

    code, _, err = _run_cli(
        ["--request-id", "req-missing", "--run-key", "0" * 32, "--input", str(trace_path)]
    )
    assert code == 4 and "未找到" in err


def test_old_trace_without_run_key_remains_readable(tmp_path):
    """兼容策略锁定：trace_version 不变，run_key 为可选字段。

    旧记录（无 run_key）只给 --request-id 必须照常可读；给 --run-key 时因无法精确
    匹配返回既有「未找到」退出码 4，不得渲染错行。
    """
    trace_path = tmp_path / "agent-trace.jsonl"
    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "库存？", request_id="req-old-1"
        )
    )
    records = read_records(trace_path)
    assert records[0]["trace_version"] == 1
    records[0]["run"].pop("run_key")
    trace_path.write_text(
        json.dumps(records[0], ensure_ascii=False) + "\n", encoding="utf-8"
    )

    code, out, err = _run_cli(["--request-id", "req-old-1", "--input", str(trace_path)])
    assert code == 0 and err == ""
    assert "request_id=req-old-1" in out
    assert "run_key=" not in out  # 渲染层不臆造旧记录缺失的字段

    code, _, err = _run_cli(
        ["--request-id", "req-old-1", "--run-key", "0" * 32, "--input", str(trace_path)]
    )
    assert code == 4 and "未找到" in err


def test_cli_run_key_filter_preserves_bad_line_and_unreadable_exit_codes(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "库存？", request_id="req-rk-bad"
        )
    )
    record = read_records(trace_path)[0]
    run_key = record["run"]["run_key"]
    trace_path.write_text(
        "{broken\n" + json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    code, out, err = _run_cli(
        ["--request-id", "req-rk-bad", "--run-key", run_key, "--input", str(trace_path)]
    )
    assert code == 0 and "跳过 1 行无法解析" in err
    assert f"run_key={run_key}" in out

    garbage = tmp_path / "garbage.jsonl"
    garbage.write_text("{broken\n", encoding="utf-8")
    code, _, err = _run_cli(
        ["--request-id", "req-x", "--run-key", run_key, "--input", str(garbage)]
    )
    assert code == 5 and "无法解析" in err

    missing = tmp_path / "nope.jsonl"
    code, _, err = _run_cli(
        ["--request-id", "req-x", "--run-key", run_key, "--input", str(missing)]
    )
    assert code == 3 and "不存在" in err


# ---------------------------------------------------------------- CLI 流式读取


def test_cli_reads_line_by_line_without_read_text(tmp_path, monkeypatch):
    trace_path = tmp_path / "agent-trace.jsonl"
    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "库存？", request_id="req-stream-1"
        )
    )

    def _fail_read_text(self, *args, **kwargs):
        raise AssertionError("CLI 不应整文件 read_text")

    monkeypatch.setattr(Path, "read_text", _fail_read_text)
    code, out, err = _run_cli(
        ["--request-id", "req-stream-1", "--input", str(trace_path)]
    )
    assert code == 0 and err == ""
    assert "request_id=req-stream-1" in out


def test_cli_bad_line_accounting_and_last_match(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "库存？", request_id="req-stream-2"
        )
    )
    good = trace_path.read_text(encoding="utf-8")
    trace_path.write_text(
        good + "{broken\n" + good.replace("req-stream-2", "req-other") + good,
        encoding="utf-8",
    )

    code, out, err = _run_cli(
        ["--request-id", "req-stream-2", "--input", str(trace_path)]
    )
    assert code == 0
    assert "找到 2 条同 request_id 记录" in err
    assert "跳过 1 行无法解析" in err
    assert out.count("request_id=req-stream-2 ") == 1
