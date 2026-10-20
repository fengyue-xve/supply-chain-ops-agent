"""模型调用网关：provider 调用 + 剩余预算硬超时 + 错误映射。

受 Pi StreamFn 契约（不抛异常，失败编码进消息/结果）启发：超时与供应商错误统一编码为
ModelCallOutcome(error_class=...)，由编排器决定降级文案与停止原因，provider 细节不进入
轮循环。调用方负责先检查剩余预算（<=0 不发起，语义见 orchestrator）。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from app.models.base import ModelProvider, ModelResponse
from app.models.errors import ModelProviderError


@dataclass(frozen=True)
class ModelCallOutcome:
    response: ModelResponse | None = None
    error_class: str | None = None


class ModelGateway:
    def __init__(self, provider: ModelProvider) -> None:
        self._provider = provider

    async def complete(
        self, messages: list[dict], tools: list[dict] | None, *, timeout: float
    ) -> ModelCallOutcome:
        try:
            response = await asyncio.wait_for(
                self._provider.complete(messages, tools=tools),
                timeout=timeout,
            )
        except (asyncio.TimeoutError, ModelProviderError) as exc:
            return ModelCallOutcome(error_class=type(exc).__name__)
        return ModelCallOutcome(response=response)
