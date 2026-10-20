"""AgentRun 上下文：request_id/conversation_id/总预算 deadline/轮次/结束状态。

受 Pi runLoop 的 turn 快照与 stopReason 收口思想启发：所有退出路径显式记录
stop_reason，run.status 由它归口（degraded = 走了降级文案/预检查短路），Trace 的
「结束状态与停止/降级原因」以此为准。
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Callable

STOP_ANSWERED = "answered"
STOP_TOOL_ASSEMBLED = "tool_assembled"
STOP_ROUND_LIMIT = "round_limit"
STOP_BUDGET_EXHAUSTED = "budget_exhausted"
STOP_MODEL_ERROR = "model_error"
STOP_SCHEMA_FAILURE = "schema_failure"
STOP_CANCELLED = "cancelled"
STOP_FALLBACK = "fallback"
STOP_UNKNOWN = "unknown"

DEGRADED_STOP_REASONS = frozenset(
    {STOP_BUDGET_EXHAUSTED, STOP_MODEL_ERROR, STOP_SCHEMA_FAILURE, STOP_ROUND_LIMIT}
)


@dataclass
class RunContext:
    request_id: str
    conversation_id: str
    started_monotonic: float
    deadline: float
    total_budget_seconds: float
    max_rounds: int
    started_wall: str
    rounds_used: int = 0
    stop_reason: str | None = None
    status: str = "ok"
    tool_call_count: int = 0
    model_tools_count: int = 0
    schema_source: str | None = None
    sources_ids: list[str] = field(default_factory=list)
    # 可注入时钟：生产用 time.monotonic，测试注入假时钟做确定性预算断言。
    clock: Callable[[], float] = time.monotonic
    run_key: str = field(default_factory=lambda: uuid.uuid4().hex)

    @property
    def remaining_seconds(self) -> float:
        return self.deadline - self.clock()

    def finish(self) -> None:
        """结束状态归口：未标记 stop_reason 的异常收尾按 unknown 如实记录。

        取消（cancelled）由 orchestrator 捕获 CancelledError 时显式标记后原样抛出，
        与 degraded/error 区分：不是降级也不是未预期异常，是任务被上层中断。
        """
        if self.stop_reason is None:
            self.stop_reason = STOP_UNKNOWN
        if self.stop_reason == STOP_UNKNOWN:
            self.status = "error"
        elif self.stop_reason == STOP_CANCELLED:
            self.status = "cancelled"
        elif self.stop_reason in DEGRADED_STOP_REASONS:
            self.status = "degraded"
