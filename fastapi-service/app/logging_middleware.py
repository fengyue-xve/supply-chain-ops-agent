"""HTTP 中间件：校验/透传 X-Request-Id，写入 scope 并回写响应头（请求全程单一 ID）。

跨层约定（与 Java ChatController 对齐）：
- 长度上限 64（Java 同值，隐含 agent_conversation.request_id 列宽）；超长 400。
- FastAPI 额外限制字符集为可见 ASCII 且无空白/控制字符：request_id 会写入 `agent_stage`
  日志行（空格分隔的 key=value），含空白或控制字符的 id 可伪造日志字段。Java 未做字符
  校验、此处从严；合法 id（UUID 等）逐字节透传，Java 日志/DB 与 FastAPI Trace 的关联不断。
- 非法 id 按 Java 语义「不回写也不透传」（400 响应也不带 X-Request-Id）；缺失/空白生成新 id。
"""

from starlette.datastructures import Headers
from starlette.responses import JSONResponse

from app.ids import new_request_id
from app.schemas import ApiResponse

_HEADER = b"x-request-id"
MAX_REQUEST_ID_LENGTH = 64
_INVALID_REQUEST_ID_MESSAGE = (
    "X-Request-Id 只能包含可见 ASCII 字符（无空白/控制字符）且不超过 64 字符"
)


def is_valid_request_id(value: str) -> bool:
    return 1 <= len(value) <= MAX_REQUEST_ID_LENGTH and all(
        0x21 <= ord(char) <= 0x7E for char in value
    )


class RequestIdMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        raw = Headers(scope=scope).get("x-request-id")
        if raw is not None and raw.strip() and not is_valid_request_id(raw):
            # 中间件在异常处理器外层，ApiError 走不到；直接构造同一信封（app.schemas）。
            response = JSONResponse(
                status_code=400,
                content=ApiResponse.fail(400, _INVALID_REQUEST_ID_MESSAGE).model_dump(),
            )
            await response(scope, receive, send)
            return

        request_id = raw if raw is not None and raw.strip() else new_request_id()
        scope["request_id"] = request_id

        async def send_with_request_id(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers") or [])
                if not any(key == _HEADER for key, _ in headers):
                    headers.append((_HEADER, request_id.encode()))
                message = dict(message)
                message["headers"] = headers
            await send(message)

        await self.app(scope, receive, send_with_request_id)
