"""Authenticated stateless MCP Streamable HTTP (JSON response transport).

Only fixed read tools/resources are exposed. All data/permissions still come from
Java, using the caller's Authorization header; there is no server-side superuser.
"""
from __future__ import annotations
import json
import re
from urllib.parse import urlsplit
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, StrictInt, StrictStr, ValidationError
from app.errors import ApiError
from app.tools.schema_validation import argument_errors, input_schema

router = APIRouter()
VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26")
READ_NAMES = {"query-stock-level", "query-sales", "suggest-replenish"}
RECORD = {"name": "read-form-record", "description": "只读发货准备字段，不表示已发货；由Java专用reader代理。", "inputSchema": {"type": "object", "properties": {"recordId": {"type": "string", "pattern": "^R-[0-9]{4}$"}}, "required": ["recordId"], "additionalProperties": False}}
RUN = {"name": "read-form-run", "description": "只读表单执行状态、写入与回查计数，不批准不执行。", "inputSchema": {"type": "object", "properties": {"runId": {"type": "string", "pattern": "^run-[0-9a-f]{12}$"}}, "required": ["runId"], "additionalProperties": False}}

class RpcRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    jsonrpc: StrictStr
    method: StrictStr
    id: StrictStr | StrictInt | None = None
    params: dict | None = None


async def authorize(request: Request):
    authorization = request.headers.get("authorization", "")
    if not authorization.startswith("Bearer ") or len(authorization) > 8192:
        raise ApiError(401, "需要Java签发的用户Bearer凭证")
    schema = await request.app.state.java_client.authorized_schema(authorization)
    return authorization, schema


def rpc_error(identity, code, message, status=200):
    return JSONResponse({"jsonrpc": "2.0", "id": identity, "error": {"code": code, "message": message}}, status_code=status)


@router.post("/mcp")
async def mcp(request: Request):
    origin = request.headers.get("origin")
    if origin:
        source = urlsplit(origin)
        if source.scheme != "http" or source.hostname not in {"localhost", "127.0.0.1", "::1"} or source.path not in {"", "/"} or source.query or source.fragment or source.username:
            return rpc_error(None, -32600, "Origin refused", 403)
    version = request.headers.get("mcp-protocol-version", "2025-03-26")
    if version not in VERSIONS:
        return rpc_error(None, -32600, "Unsupported MCP protocol version", 400)
    if "application/json" not in request.headers.get("content-type", ""):
        return rpc_error(None, -32600, "JSON content type required", 415)
    accept = request.headers.get("accept", "*/*")
    if "application/json" not in accept and "*/*" not in accept:
        return rpc_error(None, -32600, "JSON response transport required", 406)
    authorization, schema = await authorize(request)
    raw = await request.body()
    if len(raw) > 32768:
        return rpc_error(None, -32600, "Request too large", 413)
    try:
        decoded = json.loads(raw)
        body = RpcRequest.model_validate(decoded)
    except json.JSONDecodeError:
        return rpc_error(None, -32700, "Parse error", 400)
    except ValidationError:
        return rpc_error(None, -32600, "Invalid request", 400)
    if body.jsonrpc != "2.0":
        return rpc_error(body.id, -32600, "Invalid JSON-RPC version", 400)
    if body.id is None:
        if body.method in {"notifications/initialized", "notifications/cancelled"}:
            return Response(status_code=202)
        return rpc_error(None, -32600, "Unsupported notification", 400)
    params = body.params or {}
    definitions = [definition for definition in schema.get("tools", []) if definition.get("name") in READ_NAMES and definition.get("readOnly") is True and definition.get("requiresApproval") is not True] + [RECORD, RUN]
    tools = [{"name": definition["name"], "description": definition.get("description", ""), "inputSchema": input_schema(definition),
              "annotations": {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}} for definition in definitions]
    method = body.method
    if method == "initialize":
        client_version = params.get("protocolVersion")
        result = {"protocolVersion": client_version if client_version in VERSIONS else VERSIONS[0],
                  "capabilities": {"tools": {"listChanged": False}, "resources": {"listChanged": False, "subscribe": False}},
                  "serverInfo": {"name": "supply-chain-read-agent", "version": "0.2.0"},
                  "instructions": "只读合成业务事实，必须保留快照日期。补货不建单，准备记录不证明发货。"}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        if params.get("cursor"):
            return rpc_error(body.id, -32602, "Pagination cursor not supported")
        result = {"tools": tools}
    elif method == "resources/list":
        result = {"resources": [{"uri": "supply://stock/AUD-001", "name": "AUD-001 inventory snapshot", "mimeType": "application/json"},
                                {"uri": "supply://forms/records/R-1001", "name": "Shipment preparation record", "mimeType": "application/json"}]}
    elif method == "resources/templates/list":
        result = {"resourceTemplates": [{"uriTemplate": "supply://stock/{sku}", "name": "Stock snapshot", "mimeType": "application/json"},
                                        {"uriTemplate": "supply://sales/{sku}/{days}", "name": "Sales window", "mimeType": "application/json"},
                                        {"uriTemplate": "supply://forms/records/{recordId}", "name": "Preparation record", "mimeType": "application/json"}]}
    elif method in {"tools/call", "resources/read"}:
        resource = method == "resources/read"
        if resource:
            uri = params.get("uri", "")
            if not isinstance(uri, str): return rpc_error(body.id, -32602, "Invalid resource URI")
            stock = re.fullmatch(r"supply://stock/([A-Z0-9][A-Z0-9-]{0,31})", uri)
            sales = re.fullmatch(r"supply://sales/([A-Z0-9][A-Z0-9-]{0,31})/(7|30)", uri)
            form = re.fullmatch(r"supply://forms/records/(R-[0-9]{4})", uri)
            if stock: name, arguments = "query-stock-level", {"skus": [stock[1]]}
            elif sales: name, arguments = "query-sales", {"sku": sales[1], "days": int(sales[2])}
            elif form: name, arguments = "read-form-record", {"recordId": form[1]}
            else: return rpc_error(body.id, -32602, "Resource not found")
        else:
            name, arguments = params.get("name"), params.get("arguments", {})
        definition = next((definition for definition in definitions if definition["name"] == name), None)
        if definition is None: return rpc_error(body.id, -32602, "Read tool not found")
        errors = argument_errors(definition, arguments)
        if errors: return rpc_error(body.id, -32602, "Invalid tool arguments: " + ", ".join(errors))
        if name in READ_NAMES:
            outcome = await request.app.state.java_client.call(name, arguments, authorization=authorization)
        else:
            outcome = await request.app.state.java_client.read_form("records" if name == "read-form-record" else "runs", next(iter(arguments.values())), authorization)
        if resource:
            if not outcome.ok: return rpc_error(body.id, -32002, "Resource temporarily unavailable")
            result = {"contents": [{"uri": uri, "mimeType": "application/json", "text": json.dumps(outcome.data, ensure_ascii=False)}]}
        else:
            result = {"content": [{"type": "text", "text": json.dumps(outcome.data if outcome.ok else {"error": "read_unavailable", "httpCode": outcome.http_code}, ensure_ascii=False)}], "isError": not outcome.ok}
    else:
        return rpc_error(body.id, -32601, "Method not found")
    return JSONResponse({"jsonrpc": "2.0", "id": body.id, "result": result}, headers={"MCP-Protocol-Version": version})
