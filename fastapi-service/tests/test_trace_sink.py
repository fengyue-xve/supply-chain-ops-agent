"""Trace 落盘测试：JSONL 内容/结构、脱敏红线（正文/凭证/参数值/工具错误原文不入 Trace）、
被拒工具只进 Trace 不落阶段日志、写入失败只告警一次且不影响主流程、防御性截断。

全部用 tmp_path 临时文件，不依赖真实服务与模型。
"""

import asyncio
import json
import logging

import pytest

from app.agent.events import (
    AgentEvent,
    JsonlTraceSink,
    MultiSink,
    StageLogSink,
    build_event_sink,
)
from app.agent.orchestrator import AgentOrchestrator
from app.agent.run import RunContext
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.tools.java_client import ToolCallOutcome
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload

LOGGER_NAME = "app.agent.orchestrator"

STOCK_DATA = {
    "items": [
        {
            "sku": "AUD-001",
            "productName": "无线耳机",
            "availableQty": 18,
            "stockLevel": "low",
            "snapDate": "2026-09-03",
        }
    ],
    "missingSkus": [],
    "total": 1,
}


class SchemaClient:
    async def fetch_schema(self, authorization=None):
        return schema_payload()["data"]


class DataClient:
    def __init__(self, error: str | None = None):
        self.error = error

    async def call(self, name, params, request_id=None, authorization=None):
        if self.error is not None:
            return ToolCallOutcome(ok=False, error=self.error)
        return ToolCallOutcome(ok=True, data=STOCK_DATA)


class OneToolProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        return ModelResponse(
            tool_calls=[
                ToolCallRequest(
                    name="query-stock-level", params={"skus": ["AUD-001"]}, call_id="c1"
                )
            ]
        )


class UnknownToolProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        return ModelResponse(
            tool_calls=[ToolCallRequest(name="delete-everything", params={}, call_id="c1")]
        )


class CrashingProvider(ModelProvider):
    async def complete(self, messages, tools=None):
        raise RuntimeError("unexpected provider failure")


def make(provider, client, trace_path):
    loader = ToolSchemaLoader(SchemaClient(), snapshot_path=SNAPSHOT_PATH)
    return AgentOrchestrator(
        provider, client, loader, event_sink=build_event_sink(trace_path)
    )


def read_records(trace_path):
    lines = trace_path.read_text(encoding="utf-8").strip().splitlines()
    return [json.loads(line) for line in lines]


def test_trace_record_contains_run_summary_and_stage_events(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"

    asyncio.run(
        make(OneToolProvider(), DataClient(), trace_path).chat(
            "AUD-001 库存？", request_id="req-trace-1"
        )
    )

    records = read_records(trace_path)
    assert len(records) == 1
    record = records[0]
    assert record["trace_version"] == 1
    run = record["run"]
    assert run["request_id"] == "req-trace-1"
    assert run["conversation_id"]
    assert run["stop_reason"] == "tool_assembled" and run["status"] == "ok"
    assert run["budget_seconds"] == 25.0 and run["max_rounds"] == 5
    assert run["rounds_used"] == 1 and run["tool_calls"] == 1
    assert run["model_tools"] == 3 and run["schema_source"] == "java"
    assert run["sources"] == []
    assert run["event_count"] == len(record["events"])

    events = record["events"]
    assert [event["type"] for event in events] == [
        "schema_load",
        "rag_retrieve",
        "model_call",
        "tool_call",
        "response_assemble",
    ]
    rag = events[1]
    assert rag["retriever_status"] == "empty"
    assert rag["hit_ids"] == [] and rag["injected_ids"] == [] and rag["corpus_chunks"] == 0
    model_call = events[2]
    assert model_call["round"] == 1
    assert model_call["tool_names"] == ["query-stock-level"]
    tool_call = events[3]
    assert tool_call["tool"] == "query-stock-level" and tool_call["ok"] is True
    assert tool_call["param_keys"] == ["skus"] and tool_call["outcome"] == "ok"
    assemble = events[4]
    assert assemble["tool_calls"] == 1 and assemble["reply_chars"] > 0


def test_trace_never_contains_message_credentials_param_values_or_tool_error(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"

    asyncio.run(
        make(OneToolProvider(), DataClient(error="工具错误原文-敏感-7c1"), trace_path).chat(
            "库存正文不应入Trace-9f2a",
            request_id="req-trace-2",
            authorization="Bearer tok-trace-secret-123",
        )
    )

    text = trace_path.read_text(encoding="utf-8")
    assert "库存正文不应入Trace-9f2a" not in text
    assert "tok-trace-secret-123" not in text and "Bearer" not in text
    assert "AUD-001" not in text  # 参数值只记键名，不记值
    assert "工具错误原文-敏感-7c1" not in text

    tool_call = read_records(trace_path)[0]["events"][3]
    assert tool_call["ok"] is False and tool_call["outcome"] == "java_error"


def test_blocked_tool_goes_to_trace_only_not_stage_logs(tmp_path, caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    trace_path = tmp_path / "agent-trace.jsonl"

    asyncio.run(
        make(UnknownToolProvider(), DataClient(), trace_path).chat(
            "请清空数据", request_id="req-trace-3"
        )
    )

    stage_lines = [
        record.getMessage()
        for record in caplog.records
        if record.name == LOGGER_NAME
        and record.getMessage().startswith("agent_stage")
    ]
    assert not [line for line in stage_lines if "stage=tool_call" in line]

    events = read_records(trace_path)[0]["events"]
    blocked = [event for event in events if event["type"] == "tool_blocked"]
    assert len(blocked) == 1
    assert blocked[0]["tool"] == "delete-everything"
    assert blocked[0]["reason"] == "unknown_tool"


def test_trace_write_failure_warns_once_and_never_breaks_chat(tmp_path, caplog):
    caplog.set_level(logging.WARNING, logger=LOGGER_NAME)
    sink = JsonlTraceSink(tmp_path)  # 目录当文件：必然写入失败
    loader = ToolSchemaLoader(SchemaClient(), snapshot_path=SNAPSHOT_PATH)
    orchestrator = AgentOrchestrator(
        OneToolProvider(), DataClient(), loader, event_sink=sink
    )

    first = asyncio.run(orchestrator.chat("库存？", request_id="req-trace-4a"))
    second = asyncio.run(orchestrator.chat("库存？", request_id="req-trace-4b"))

    assert first.reply and second.reply  # 主流程不受影响
    warnings = [
        record for record in caplog.records if "trace_write_failed" in record.getMessage()
    ]
    assert len(warnings) == 1


def test_unexpected_chat_error_is_recorded_as_error_status(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    with pytest.raises(RuntimeError, match="unexpected provider failure"):
        asyncio.run(
            make(CrashingProvider(), DataClient(), trace_path).chat(
                "库存？", request_id="req-trace-unexpected-error"
            )
        )

    run = read_records(trace_path)[0]["run"]
    assert run["stop_reason"] == "unknown"
    assert run["status"] == "error"


def test_trace_sink_sanitizes_overlong_and_non_scalar_fields(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    sink = JsonlTraceSink(trace_path)
    run = RunContext(
        request_id="req-safe",
        conversation_id="conv-safe",
        started_monotonic=0.0,
        deadline=1.0,
        total_budget_seconds=1.0,
        max_rounds=1,
        started_wall="2026-10-04T00:00:00+00:00",
    )
    sink.begin(run)
    sink.emit(
        AgentEvent(
            type="custom",
            request_id="req-safe",
            run_key=run.run_key,
            fields={"long": "y" * 500, "obj": object(), "seq": [1, "z" * 300]},
        )
    )
    sink.end(run)

    event = read_records(trace_path)[0]["events"][0]
    assert event["long"] == "y" * 200 + "<truncated>"
    assert event["obj"] == "<object>"
    assert event["seq"] == [1, "z" * 200 + "<truncated>"]


def test_trace_sink_keeps_concurrent_runs_with_same_request_id_separate(tmp_path):
    trace_path = tmp_path / "agent-trace.jsonl"
    sink = JsonlTraceSink(trace_path)

    def make_run():
        return RunContext(
            request_id="duplicate-request-id",
            conversation_id="conv-safe",
            started_monotonic=0.0,
            deadline=1.0,
            total_budget_seconds=1.0,
            max_rounds=1,
            started_wall="2026-10-04T00:00:00+00:00",
        )

    first, second = make_run(), make_run()
    sink.begin(first)
    sink.begin(second)
    sink.emit(
        AgentEvent(
            type="probe",
            request_id=first.request_id,
            run_key=first.run_key,
            fields={"owner": "first"},
        )
    )
    sink.emit(
        AgentEvent(
            type="probe",
            request_id=second.request_id,
            run_key=second.run_key,
            fields={"owner": "second"},
        )
    )
    sink.end(first)
    sink.end(second)

    records = read_records(trace_path)
    assert len(records) == 2
    assert [record["events"][0]["owner"] for record in records] == ["first", "second"]
    assert all(len(record["events"]) == 1 for record in records)
    assert all("run_key" not in record["events"][0] for record in records)


def test_build_event_sink_defaults_and_composition(tmp_path):
    assert isinstance(build_event_sink(None), StageLogSink)
    assert isinstance(build_event_sink(tmp_path / "t.jsonl"), MultiSink)


def test_stage_log_sink_ignores_non_stage_events_and_skips_none_fields(caplog):
    caplog.set_level(logging.INFO, logger=LOGGER_NAME)
    sink = StageLogSink()
    sink.emit(AgentEvent(type="tool_blocked", request_id="req-x", fields={"round": 1}))
    sink.emit(
        AgentEvent(
            type="rag_retrieve",
            request_id="req-x",
            duration_ms=3,
            fields={"chunks": None, "error_class": None},
        )
    )

    lines = [
        record.getMessage()
        for record in caplog.records
        if record.name == LOGGER_NAME
    ]
    assert lines == ["agent_stage stage=rag_retrieve request_id=req-x duration_ms=3"]
