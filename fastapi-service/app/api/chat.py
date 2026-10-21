"""Gateway HTTP 面：/agent/health（探活）与 /agent/chat（单轮对话）。

/agent/chat 需要调用方携带用户 Authorization，并把同一凭证逐轮透传给 Java 工具接口；
Java 是唯一 JWT 验签与 ACL 边界（W2-D1/D2），本层缺失 Authorization 时的 401 只表示
「请求上下文缺失保护」，不做任何权限判定。
"""

from fastapi import APIRouter, Request

from app.errors import ApiError
from app.ids import new_request_id
from app.schemas import ApiResponse, ChatRequest
from app.agent.live_trace import trace_identity

router = APIRouter()

_AUTH_MISSING_MESSAGE = "缺少用户凭证：请通过 Java /api/chat 携带 Authorization 访问"


@router.get("/agent/health")
async def health(request: Request) -> dict:
    state = request.app.state
    java_up = await state.java_client.ping()
    schema = None
    if java_up:
        try:
            # 健康检查无用户上下文:不带 Authorization(schema 不可用时由 Java 401 触发快照回退,
            # 不影响健康语义,也绝不让本层持有或判定用户凭证)。
            schema = await state.schema_loader.load()
        except Exception:
            schema = None
    return {
        "status": "ok",
        "provider": state.provider.name,
        "java_up": java_up,
        "tools_count": len(schema.get("tools", [])) if schema else None,
        "schema_source": state.schema_loader.source,
    }


@router.post("/agent/chat")
async def chat(body: ChatRequest, request: Request) -> ApiResponse:
    authorization = request.headers.get("authorization")
    if not authorization:
        raise ApiError(401, _AUTH_MISSING_MESSAGE)
    request_id = request.scope.get("request_id") or new_request_id()
    with trace_identity(authorization):
        data = await request.app.state.orchestrator.chat(
            message=body.message,
            conversation_id=body.conversation_id,
            request_id=request_id,
            authorization=authorization,
            history=body.history,
        )
    payload = data.model_dump()
    if not data.sources:
        # 无命中时完全省略 sources 键（只做单字段裁剪，不启用全局 exclude_none）
        payload.pop("sources", None)
    return ApiResponse.ok(payload)
