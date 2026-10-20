"""Trace CLI：按 request_id 读取 Agent Trace（JSONL），渲染树形时间线或输出 JSON。

用法：
    python -m app.trace_cli --request-id <id> [--run-key <key>] [--input <trace-file>] [--json]

--run-key 可选：同 request_id 并发运行时用它精确选择单条 run（run_key 为内部随机标识，
落盘在 run 段；旧记录可能没有该字段，只给 --request-id 的既有行为不变）。
--input 缺省顺序：命令行参数 → 环境变量 AGENT_TRACE_FILE → logs/agent-trace.jsonl。
Trace 文件由 JsonlTraceSink 追加写入（每 run 一行 JSON），开启方式见 fastapi-service/README.md。

退出码：0 = 成功；2 = 用法错误（argparse）；3 = Trace 文件不存在或不可读；
4 = 文件中不存在该 request_id（给了 --run-key 时为二者精确匹配不到）；
5 = 记录格式错误或 trace_version 不兼容。
文件逐行流式扫描（不做整文件 read_text，内存与匹配记录数相关；坏行跳过并提示）；
只读取本地文件，不访问网络、不触发任何 Agent 运行。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from app.agent.events import TRACE_VERSION

DEFAULT_TRACE_PATH = Path(__file__).resolve().parents[1] / "logs" / "agent-trace.jsonl"

_REQUIRED_RUN_KEYS = (
    "request_id",
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


def resolve_trace_path(cli_input: str | None) -> Path:
    if cli_input:
        return Path(cli_input)
    env_value = os.environ.get("AGENT_TRACE_FILE", "").strip()
    if env_value:
        return Path(env_value)
    return DEFAULT_TRACE_PATH


def scan_trace_lines(
    path: Path, request_id: str, run_key: str | None = None
) -> tuple[list[Any], int, list[tuple[int, str]]]:
    """逐行流式扫描 Trace 文件，返回 (匹配记录, 有效记录总数, 坏行列表)。

    只给 request_id 时匹配其全部记录（行为与旧版一致）；给 run_key 时须二者同时
    精确匹配（旧记录无 run_key 字段时匹配不到，不会误选）。不整文件 read_text：
    内存只与匹配记录数 + 坏行数相关；空行跳过。
    """
    matches: list[Any] = []
    valid_count = 0
    bad_lines: list[tuple[int, str]] = []
    with path.open("r", encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                record = json.loads(stripped)
            except json.JSONDecodeError as exc:
                bad_lines.append((lineno, str(exc)))
                continue
            valid_count += 1
            run = record.get("run") if isinstance(record, dict) else None
            if isinstance(run, dict) and run.get("request_id") == request_id:
                if run_key is None or run.get("run_key") == run_key:
                    matches.append(record)
    return matches, valid_count, bad_lines


def validate_record(record: Any) -> str | None:
    if not isinstance(record, dict):
        return "记录不是 JSON 对象"
    version = record.get("trace_version")
    if version != TRACE_VERSION:
        return f"trace_version={version!r} 不兼容（本 CLI 支持 {TRACE_VERSION}）"
    run = record.get("run")
    if not isinstance(run, dict):
        return "缺少 run 段"
    missing = [key for key in _REQUIRED_RUN_KEYS if key not in run]
    if missing:
        return f"run 段缺少字段：{missing}"
    events = record.get("events")
    if not isinstance(events, list):
        return "events 段缺失或不是列表"
    for index, event in enumerate(events):
        if not isinstance(event, dict):
            return f"events[{index}] 不是 JSON 对象"
        if not isinstance(event.get("type"), str):
            return f"events[{index}].type 缺失或不是字符串"
    return None


def format_value(value: Any) -> str:
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def render_timeline(record: dict) -> str:
    run = record["run"]
    lines = [
        f"request_id={run['request_id']} conversation_id={run['conversation_id']}",
    ]
    if run.get("run_key"):
        lines.append(f"run_key={run['run_key']}")  # 旧记录无该字段时不渲染
    lines += [
        "status={status} stop_reason={stop_reason} duration_ms={duration_ms} "
        "budget_seconds={budget_seconds}".format(**run),
        "started_at={started_at} rounds_used={rounds_used}/{max_rounds} "
        "tool_calls={tool_calls} model_tools={model_tools} schema_source={schema_source}".format(**run),
    ]
    if run.get("sources"):
        lines.append(f"sources={format_value(run['sources'])}")
    events = record.get("events") or []
    lines.append(f"events={len(events)}")
    for index, event in enumerate(events):
        connector = "└─" if index == len(events) - 1 else "├─"
        parts = [f"{connector} {event.get('type', '?')}"]
        if event.get("duration_ms") is not None:
            parts.append(f"({event['duration_ms']}ms)")
        for key, value in event.items():
            if key in ("type", "duration_ms") or value is None:
                continue
            parts.append(f"{key}={format_value(value)}")
        lines.append(" ".join(parts))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m app.trace_cli", description="按 request_id 读取 Agent Trace（JSONL 本地文件）"
    )
    parser.add_argument("--request-id", required=True, help="要查询的 request_id")
    parser.add_argument(
        "--run-key",
        default=None,
        help="可选：内部 run_key，与 --request-id 精确匹配单条 run（同 request_id 并发时用）",
    )
    parser.add_argument(
        "--input",
        default=None,
        help="Trace 文件路径（缺省 AGENT_TRACE_FILE 环境变量，再缺省 logs/agent-trace.jsonl）",
    )
    parser.add_argument("--json", action="store_true", help="输出完整 JSON 记录（默认树形时间线）")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return int(exc.code or 2)

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    path = resolve_trace_path(args.input)
    if not path.is_file():
        print(f"[错误] Trace 文件不存在：{path}", file=sys.stderr)
        return 3
    try:
        matches, valid_count, bad_lines = scan_trace_lines(
            path, args.request_id, args.run_key
        )
    except (OSError, UnicodeDecodeError) as exc:
        print(f"[错误] Trace 文件不可读：{path}（{type(exc).__name__}）", file=sys.stderr)
        return 3

    if valid_count == 0 and bad_lines:
        lineno, reason = bad_lines[0]
        print(f"[错误] Trace 文件无法解析：第 {lineno} 行（{reason}）", file=sys.stderr)
        return 5

    if not matches:
        target = f"request_id={args.request_id}"
        if args.run_key:
            target += f" run_key={args.run_key}"
        print(
            f"[未找到] {target}（文件 {path}，有效记录 {valid_count} 条）",
            file=sys.stderr,
        )
        return 4
    record = matches[-1]
    if len(matches) > 1:
        print(f"[提示] 找到 {len(matches)} 条同 request_id 记录，渲染最后一条", file=sys.stderr)

    problem = validate_record(record)
    if problem:
        print(f"[错误] Trace 记录不可用：{problem}", file=sys.stderr)
        return 5

    if args.json:
        print(json.dumps(record, ensure_ascii=False, indent=2))
    else:
        print(render_timeline(record))
    if bad_lines:
        print(
            f"[提示] 跳过 {len(bad_lines)} 行无法解析的内容（文件可能在追加写入）",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
