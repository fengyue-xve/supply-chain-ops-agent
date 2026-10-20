"""三类跟踪 ID：req- 请求 / conv- 会话 / tc- 工具调用。前缀语义可审计、可跨服务透传。"""

import uuid

_HEX_LENGTH = {"req": 12, "conv": 12, "tc": 8}


def _new(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:_HEX_LENGTH[prefix]]}"


def new_request_id() -> str:
    return _new("req")


def new_conversation_id() -> str:
    return _new("conv")


def new_tool_call_id() -> str:
    return _new("tc")
