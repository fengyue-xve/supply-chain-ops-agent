"""Agent 事件与 Trace：受 Pi agent-loop 事件总线（emit/AgentEvent）启发的 Python 最小实现。

所有阶段/运行事件经 EventSink 汇聚：
- StageLogSink 把五个阶段事件渲染为既有 `agent_stage` 行（字段与格式与此前逐字一致）；
  其他事件类型（如被拒工具）不落阶段日志，保持五阶段日志口径不变。
- JsonlTraceSink 把每个 run 一行 JSON（trace_version=1）追加写入 Trace 文件；写入失败
  只记一次告警、绝不抛出（不影响主流程）。

安全：事件字段由各发射点构造（只允许标识/计数/键名/耗时/固定类别）；TraceSink 再做一层
防御（长字符串截断、非标量降级为类型名），并有测试锁定「用户正文 / 凭证 / 参数值 /
工具错误原文不入 Trace」。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Protocol

if TYPE_CHECKING:
    from app.agent.run import RunContext

# 沿用固定 logger 名：既有阶段日志测试与外部日志口径均按 app.agent.orchestrator 过滤。
logger = logging.getLogger("app.agent.orchestrator")

STAGE_TYPES = (
    "schema_load",
    "rag_retrieve",
    "model_call",
    "tool_call",
    "response_assemble",
)

TRACE_VERSION = 1
_MAX_FIELD_CHARS = 200


@dataclass
class AgentEvent:
    type: str
    request_id: str
    duration_ms: int | None = None
    fields: dict[str, Any] = field(default_factory=dict)
    trace_fields: dict[str, Any] = field(default_factory=dict)
    run_key: str | None = None


class EventSink(Protocol):
    def begin(self, run: "RunContext") -> None: ...

    def emit(self, event: AgentEvent) -> None: ...

    def end(self, run: "RunContext") -> None: ...


class StageLogSink:
    """五阶段事件 → 既有 agent_stage INFO 行；其余事件类型直接忽略。"""

    def begin(self, run: "RunContext") -> None:
        pass

    def emit(self, event: AgentEvent) -> None:
        if event.type not in STAGE_TYPES:
            return
        parts = [
            f"agent_stage stage={event.type}",
            f"request_id={_log_safe(event.request_id)}",
        ]
        if event.duration_ms is not None:
            parts.append(f"duration_ms={event.duration_ms}")
        parts.extend(f"{key}={value}" for key, value in event.fields.items() if value is not None)
        logger.info(" ".join(parts))

    def end(self, run: "RunContext") -> None:
        pass


class JsonlTraceSink:
    """每个 run 一行 JSON 追加写入；写入失败告警一次，不抛异常。"""

    def __init__(self, path: Path | str) -> None:
        self._path = Path(path)
        self._runs: dict[str, list[dict]] = {}
        self._warned = False

    def begin(self, run: "RunContext") -> None:
        self._runs[run.run_key] = []

    def emit(self, event: AgentEvent) -> None:
        run_key = event.run_key or event.request_id
        self._runs.setdefault(run_key, []).append(_event_record(event))

    def end(self, run: "RunContext") -> None:
        events = self._runs.pop(run.run_key, [])
        record = {
            "trace_version": TRACE_VERSION,
            "run": _run_summary(run, events),
            "events": events,
        }
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            with open(self._path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        except (OSError, TypeError, ValueError) as exc:
            if not self._warned:
                self._warned = True
                logger.warning(
                    "trace_write_failed path=%s error=%s", self._path, type(exc).__name__
                )


class MultiSink:
    def __init__(self, *sinks: EventSink) -> None:
        self._sinks = sinks

    def begin(self, run: "RunContext") -> None:
        for sink in self._sinks:
            sink.begin(run)

    def emit(self, event: AgentEvent) -> None:
        for sink in self._sinks:
            sink.emit(event)

    def end(self, run: "RunContext") -> None:
        for sink in self._sinks:
            sink.end(run)


def build_event_sink(trace_file: Path | str | None = None) -> EventSink:
    if trace_file:
        return MultiSink(StageLogSink(), JsonlTraceSink(trace_file))
    return StageLogSink()


def _event_record(event: AgentEvent) -> dict:
    record: dict[str, Any] = {"type": event.type}
    if event.duration_ms is not None:
        record["duration_ms"] = event.duration_ms
    for key, value in event.fields.items():
        record[key] = _safe_value(value)
    for key, value in event.trace_fields.items():
        record[key] = _safe_value(value)
    return record


def _log_safe(value: str) -> str:
    """日志行渲染防线：可见 ASCII 之外的字符转义为 \\xNN/\\uNNNN，防字段伪造。

    HTTP 面的非法 id 已在 RequestIdMiddleware 400 拦截；此处兜住进程内直接调用
    （测试/评测/未来入口）传入的任意字符串。
    """
    escaped = []
    for char in value:
        code = ord(char)
        if 0x21 <= code <= 0x7E:
            escaped.append(char)
        elif code < 0x100:
            escaped.append(f"\\x{code:02x}")
        else:
            escaped.append(f"\\u{code:04x}")
    return "".join(escaped)


def _run_summary(run: "RunContext", events: list[dict]) -> dict:
    # run_key（内部随机标识，非用户输入）落盘用于同 request_id 并发运行的唯一化。
    # 兼容策略：trace_version 不变，run_key 为可选字段——旧记录无此键仍可读（CLI 不要求）。
    summary = {
        "request_id": run.request_id,
        "run_key": run.run_key,
        "conversation_id": run.conversation_id,
        "started_at": run.started_wall,
        "duration_ms": int((time.monotonic() - run.started_monotonic) * 1000),
        "budget_seconds": run.total_budget_seconds,
        "max_rounds": run.max_rounds,
        "rounds_used": run.rounds_used,
        "stop_reason": run.stop_reason,
        "status": run.status,
        "tool_calls": run.tool_call_count,
        "model_tools": run.model_tools_count,
        "schema_source": run.schema_source,
        "sources": list(run.sources_ids),
        "event_count": len(events),
    }
    return {key: _safe_value(value) for key, value in summary.items()}


def _safe_value(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        # Trace is also rendered by the local CLI. Escape control characters here,
        # not only at the HTTP boundary, so direct/in-process emitters cannot inject
        # line breaks into the human-readable timeline or JSONL diagnostics.
        safe = "".join(
            char
            if char.isprintable()
            else (f"\\x{ord(char):02x}" if ord(char) <= 0xFF else f"\\u{ord(char):04x}")
            for char in value
        )
        if len(safe) <= _MAX_FIELD_CHARS:
            return safe
        return safe[:_MAX_FIELD_CHARS] + "<truncated>"
    if isinstance(value, (list, tuple)):
        return [_safe_value(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _safe_value(item) for key, item in value.items()}
    return f"<{type(value).__name__}>"
