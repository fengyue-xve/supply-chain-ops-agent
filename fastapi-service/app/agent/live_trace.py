"""Bounded, short-lived live traces bound to the exact caller credential hash.

No raw prompt, history, tool arguments, observations or bearer tokens are stored.
The existing JSONL event format is deliberately unchanged.
"""
from __future__ import annotations
import asyncio
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import time

_owner: ContextVar[str] = ContextVar("trace_owner", default="")
KNOWN_TOOLS = {"query-stock-level", "query-sales", "suggest-replenish"}
PARAMETER_NAMES = {"sku", "skus", "category", "days", "targetCoverDays"}


def owner_hash(authorization: str) -> str:
    return hashlib.sha256(authorization.encode()).hexdigest()


@contextmanager
def trace_identity(authorization: str):
    token = _owner.set(owner_hash(authorization))
    try:
        yield
    finally:
        _owner.reset(token)


class LiveTraceSink:
    def __init__(self, limit=100, ttl_seconds=900):
        self.limit, self.ttl_seconds = limit, ttl_seconds
        self._records = {}
        self._keys = {}

    def _prune(self):
        for key, record in list(self._records.items()):
            if time.monotonic() - record["created"] > self.ttl_seconds:
                self._keys.pop(record["run"].run_key, None)
                self._records.pop(key, None)
        while len(self._records) >= self.limit:
            key = next(iter(self._records))
            record = self._records.pop(key)
            self._keys.pop(record["run"].run_key, None)

    def begin(self, run):
        identity = _owner.get()
        if not identity:
            return
        self._prune()
        key = (identity, run.request_id)
        previous = self._records.get(key)
        if previous:
            self._keys.pop(previous["run"].run_key, None)
        self._records[key] = {"run": run, "created": time.monotonic(), "events": [], "finished": False,
                              "task": asyncio.current_task(), "cancel_requested": False}
        self._keys[run.run_key] = key

    def emit(self, event):
        key = self._keys.get(event.run_key)
        record = self._records.get(key)
        if not record:
            return
        row = {"seq": len(record["events"]) + 1, "type": event.type, "duration_ms": event.duration_ms}
        for name in ("round", "ok", "chunks", "error_class"):
            if name in event.fields:
                row[name] = event.fields[name]
        tool = event.fields.get("tool")
        if tool:
            row["tool"] = tool if tool in KNOWN_TOOLS else "unregistered"
            reason = event.trace_fields.get("reason")
            row["schema_validation"] = "failed" if reason in {"parse_error", "schema_validation_failed"} else "passed" if event.type == "tool_call" else "not_checked"
        if "reason" in event.trace_fields:
            row["reason"] = event.trace_fields["reason"]
        if "param_keys" in event.trace_fields:
            row["parameter_names"] = [key for key in event.trace_fields["param_keys"] if key in PARAMETER_NAMES]
        if "injected_ids" in event.trace_fields:
            row["reference_ids"] = event.trace_fields["injected_ids"][:3]
        if len(record["events"]) < 128:
            record["events"].append(row)

    def end(self, run):
        record = self._records.get(self._keys.get(run.run_key))
        if record:
            record["finished"] = True
            record["ended"] = time.monotonic()
            record["task"] = None

    def get(self, authorization, request_id):
        record = self._records.get((owner_hash(authorization), request_id))
        if not record or time.monotonic() - record["created"] > self.ttl_seconds:
            return None
        run = record["run"]
        return {"request_id": run.request_id, "status": run.status if record["finished"] else "running",
                "stop_reason": run.stop_reason if record["finished"] else None,
                "rounds_used": run.rounds_used, "max_rounds": run.max_rounds,
                "duration_ms": int((record.get("ended", time.monotonic()) - run.started_monotonic) * 1000),
                "finished": record["finished"], "cancel_requested": record["cancel_requested"],
                "context_layers": getattr(run, "context_layers", []), "events": list(record["events"])}

    def cancel(self, authorization, request_id):
        record = self._records.get((owner_hash(authorization), request_id))
        if not record or time.monotonic() - record["created"] > self.ttl_seconds:
            return False
        if not record["finished"] and record["task"]:
            record["cancel_requested"] = True
            record["task"].cancel()
        return True


def context_layout(history, chunks, messages):
    return [
        {"kind": "system", "messages": 1, "characters": len(messages[0]["content"]), "description": "唯一静态系统指令"},
        {"kind": "history", "messages": len(history or []), "characters": sum(len(item.content) for item in history or []), "description": "Java按本人会话归属裁剪的文本历史"},
        {"kind": "reference", "messages": 1 if chunks else 0, "characters": sum(len(chunk.text) for chunk in chunks), "description": "隔离的<reference-documents>参考区块，非指令", "chunk_ids": [chunk.chunk_id for chunk in chunks]},
        {"kind": "user", "messages": 1, "characters": len(messages[-1]["content"]), "description": "本轮问题，正文不进入轨迹"},
    ]
