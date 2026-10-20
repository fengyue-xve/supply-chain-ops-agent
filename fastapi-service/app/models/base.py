"""模型提供方抽象：统一承载文本回复和 OpenAI 兼容工具调用。"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ToolCallRequest:
    name: str
    params: dict[str, Any] = field(default_factory=dict)
    call_id: str | None = None
    parse_error: str | None = None


@dataclass(frozen=True)
class ModelResponse:
    text: str | None = None
    tool_calls: list[ToolCallRequest] | None = None
    # 真实模型的 assistant 消息需要原样放回下一轮上下文；Fake 不需要。
    assistant_message: dict[str, Any] | None = None


class ModelProvider(ABC):
    name: str = "base"

    @abstractmethod
    async def complete(
        self, messages: list[dict], tools: list[dict] | None = None
    ) -> ModelResponse:
        """messages: [{"role": "user", "content": ...}]；tools: 契约 tools[] 元数据。"""
        raise NotImplementedError
