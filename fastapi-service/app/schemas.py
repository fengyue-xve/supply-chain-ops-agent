"""/agent/chat 请求响应与统一信封的 Pydantic 模型（信封与 Java ApiResponse 对齐）。"""

from typing import Any, Literal

from pydantic import BaseModel, Field


class HistoryMessage(BaseModel):
    """历史消息（W4-1）：只允许 user/assistant 纯文本，由 Java 按归属裁剪后下发。

    本层不落库、不持久化；协议消息（tool/system）不在契约内，Java 不会发送，
    越权候选直接 400。条数/长度上限是外层防御，真实裁剪边界在 Java。
    """

    role: Literal["user", "assistant"]
    content: str = Field(max_length=2200)


class ChatRequest(BaseModel):
    conversation_id: str | None = None
    message: str = Field(max_length=2000)
    history: list[HistoryMessage] | None = Field(default=None, max_length=32)


class ToolCallRecord(BaseModel):
    tool_call_id: str
    tool: str
    params: dict[str, Any] = Field(default_factory=dict)
    ok: bool
    round: int = 1
    summary: str | None = None
    error: str | None = None


class SourceRef(BaseModel):
    """知识引用（由代码按实际注入的 chunk 生成，模型不能自报来源）。"""

    doc: str
    section: str
    chunk_id: str


class ChatResponseData(BaseModel):
    request_id: str
    conversation_id: str
    reply: str
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    sources: list[SourceRef] | None = None


class ApiResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: Any | None = None

    @classmethod
    def ok(cls, data: Any) -> "ApiResponse":
        return cls(code=0, message="ok", data=data)

    @classmethod
    def fail(cls, code: int, message: str) -> "ApiResponse":
        return cls(code=code, message=message, data=None)
