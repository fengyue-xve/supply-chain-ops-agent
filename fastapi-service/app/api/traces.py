import re
from fastapi import APIRouter, Request
from app.api.mcp import authorize
from app.errors import ApiError
from app.schemas import ApiResponse

router = APIRouter()

@router.get("/agent/traces/{request_id}")
async def trace(request_id: str, request: Request):
    authorization, _ = await authorize(request)
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", request_id):
        raise ApiError(400, "非法请求编号")
    result = request.app.state.live_traces.get(authorization, request_id)
    if result is None:
        raise ApiError(404, "运行轨迹不存在、已过期或不属于当前凭证")
    return ApiResponse.ok(result)

@router.post("/agent/traces/{request_id}/cancel")
async def cancel(request_id: str, request: Request):
    authorization, _ = await authorize(request)
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", request_id):
        raise ApiError(400, "非法请求编号")
    if not request.app.state.live_traces.cancel(authorization, request_id):
        raise ApiError(404, "运行轨迹不存在、已过期或不属于当前凭证")
    return ApiResponse.ok({"request_id": request_id, "cancel_requested": True})
