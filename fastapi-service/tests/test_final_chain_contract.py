"""最终链路跨层确定性契约测试（A 协议 / B Harness / C 安全 / D W4 闭环）。

定位：不是新增能力，而是把「HTTP 入口 → 编排 → 工具策略 → Java 客户端（MockTransport）→
响应信封 / Trace」这条最终链路上原本分散在多份测试里的关键契约，收敛为一组端到端断言，
供交接收口时一次性核验。全部离线：Java 行为走 httpx.MockTransport，模型走 FakeModelProvider
或脚本 Provider，不调真实模型、不依赖真实 Java / MySQL / 外网。

A 协议：401/400 信封、X-Request-Id 透传与校验、成功信封形状与 sources 裁剪；
B Harness：停止语义矩阵（ok/degraded）在 RunContext 上的归口；
C 安全：模型可见工具子集、写工具/未知工具幻觉调用零触达 Java 写端点；
D W4 闭环：history→reference→user 注入顺序、sources 只由注入 chunk 生成、
   无历史逐元素兼容、HTTP 全链 Trace 落盘的字段脱敏。
"""

import asyncio
import json
import re
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

from app.agent.context import REFERENCE_FOOTER, REFERENCE_HEADER, SYSTEM_PROMPT
from app.agent.orchestrator import (
    MODEL_FAILURE_REPLY,
    SCHEMA_FAILURE_REPLY,
    AgentOrchestrator,
)
from app.agent.run import (
    STOP_ANSWERED,
    STOP_BUDGET_EXHAUSTED,
    STOP_MODEL_ERROR,
    STOP_ROUND_LIMIT,
    STOP_TOOL_ASSEMBLED,
)
from app.main import create_app
from app.models.base import ModelProvider, ModelResponse, ToolCallRequest
from app.models.errors import ModelProviderError
from app.models.fake import FakeModelProvider
from app.rag.retriever import EmptyRetriever
from app.rag.retriever_v2 import KnowledgeRetrieverV2
from app.tools.java_client import JavaToolClient, ToolCallOutcome
from app.tools.schema_loader import ToolSchemaLoader
from tests.conftest import SNAPSHOT_PATH, schema_payload

DEFAULT_AUTH = "Bearer unit-admin-token-final"
TOOL_CALL_SUFFIX = "\n（已达到工具调用轮数上限，请根据以上结果判断。）"

STOCK_DATA = {
    "items": [
        {
            "sku": "AUD-001",
            "productName": "无线耳机",
            "availableQty": 18,
            "stockLevel": "low",
            "snapDate": "2026-09-03",
        }
    ],
    "missingSkus": [],
    "total": 1,
}

KNOWLEDGE_DOC = """# 供应链运营知识

## [purchase-approval] 采购单与人工确认
创建采购单只是生成待人工确认的单据，确认动作必须由人工完成。
"""

HISTORY = [
    {"role": "user", "content": "AUD-001 现在还有多少库存？"},
    {"role": "assistant", "content": "截至 2026-09-03 快照，AUD-001 库存 18 件。"},
]


class RecordingJava:
    """httpx.MockTransport handler：记录全部请求，按契约快照与夹具响应。"""

    def __init__(self, tool_payloads: dict | None = None) -> None:
        self.tool_payloads = tool_payloads or {}
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/health":
            return httpx.Response(200, json={"status": "up"})
        if path == "/api/agent/tools/schema":
            return httpx.Response(200, json=schema_payload())
        if path.startswith("/api/agent/tools/"):
            payload = self.tool_payloads.get(path.rsplit("/", 1)[1])
            if payload is not None:
                return httpx.Response(200, json=payload)
        return httpx.Response(404, json={"code": 404, "message": "未知工具"})

    @property
    def paths(self) -> list[str]:
        return [request.url.path for request in self.requests]


def build_app(
    provider: ModelProvider,
    handler: RecordingJava,
    *,
    retriever=None,
    trace_file: Path | str | None = None,
):
    client = JavaToolClient(
        base_url="http://java.test", transport=httpx.MockTransport(handler)
    )
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    return create_app(
        java_client=client,
        provider=provider,
        schema_loader=loader,
        retriever=retriever or EmptyRetriever(),
        trace_file=trace_file,
    )


def post_chat(app, body: dict, *, authorization: str | None = DEFAULT_AUTH, request_id: str | None = None):
    headers = {}
    if authorization:
        headers["Authorization"] = authorization
    if request_id:
        headers["X-Request-Id"] = request_id
    with TestClient(app) as client:
        return client.post("/agent/chat", json=body, headers=headers)


class RecordingProvider(ModelProvider):
    name = "recording"

    def __init__(self) -> None:
        self.inner = FakeModelProvider()
        self.calls: list[tuple[list[dict], list[dict] | None]] = []

    async def complete(self, messages, tools=None):
        self.calls.append(
            ([dict(message) for message in messages], tools)
        )
        return await self.inner.complete(messages, tools=tools)


class ScriptedProvider(ModelProvider):
    """按脚本顺序返回响应（含轮次语义），用于写工具幻觉与停止矩阵。"""

    name = "scripted"

    def __init__(self, responses: list[ModelResponse]) -> None:
        self.responses = list(responses)
        self.calls = 0

    async def complete(self, messages, tools=None):
        self.calls += 1
        return self.responses.pop(0)


class StubJavaClient:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def fetch_schema(self, authorization=None):
        return schema_payload()["data"]

    async def call(self, name, params, request_id=None, authorization=None):
        self.calls.append(name)
        return ToolCallOutcome(ok=True, data=STOCK_DATA)


class CapturingSink:
    def __init__(self) -> None:
        self.runs: list = []

    def begin(self, run) -> None:
        self.runs.append(run)

    def emit(self, event) -> None:
        pass

    def end(self, run) -> None:
        pass


def run_chat(
    provider: ModelProvider,
    *,
    budget: float = 25.0,
    max_rounds: int = 5,
    message: str = "AUD-001 库存？",
):
    client = StubJavaClient()
    loader = ToolSchemaLoader(client, snapshot_path=SNAPSHOT_PATH)
    sink = CapturingSink()
    orchestrator = AgentOrchestrator(
        provider,
        client,
        loader,
        EmptyRetriever(),
        max_rounds=max_rounds,
        total_budget_seconds=budget,
        event_sink=sink,
    )
    data = asyncio.run(orchestrator.chat(message))
    return data, sink.runs[-1], client


# ===================================================================== A. 协议契约


def test_missing_authorization_is_401_envelope_and_no_downstream():
    handler = RecordingJava()
    app = build_app(FakeModelProvider(), handler)

    response = post_chat(app, {"message": "hi"}, authorization=None)

    assert response.status_code == 401
    body = response.json()
    assert set(body.keys()) == {"code", "message", "data"}
    assert body["code"] == 401 and body["data"] is None
    assert body["message"] == "缺少用户凭证：请通过 Java /api/chat 携带 Authorization 访问"
    assert handler.requests == []  # 不触达 Java


def test_invalid_payload_is_400_envelope_and_no_downstream():
    handler = RecordingJava()
    app = build_app(FakeModelProvider(), handler)

    with TestClient(app) as client:
        missing = client.post(
            "/agent/chat",
            json={"conversation_id": "conv-x"},
            headers={"Authorization": DEFAULT_AUTH},
        )
        overlong = client.post(
            "/agent/chat",
            json={"message": "x" * 2001},
            headers={"Authorization": DEFAULT_AUTH},
        )

    for response in (missing, overlong):
        assert response.status_code == 400
        body = response.json()
        assert set(body.keys()) == {"code", "message", "data"}
        assert body == {"code": 400, "message": "请求体格式错误", "data": None}
    assert handler.requests == []


def test_request_id_contract_full_chain():
    handler = RecordingJava({"query-stock-level": {"code": 0, "message": "ok", "data": STOCK_DATA}})
    app = build_app(FakeModelProvider(), handler)
    request_id = "req-final-chain-0001"

    with TestClient(app) as client:
        legal = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？"},
            headers={"Authorization": DEFAULT_AUTH, "X-Request-Id": request_id},
        )
        generated = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？"},
            headers={"Authorization": DEFAULT_AUTH},
        )
        before = len(handler.requests)
        illegal = client.post(
            "/agent/chat",
            json={"message": "hi"},
            headers={"Authorization": DEFAULT_AUTH, "X-Request-Id": "req id forged=1"},
        )

    assert legal.status_code == 200
    assert legal.headers["x-request-id"] == request_id
    assert legal.json()["data"]["request_id"] == request_id

    # 合法 id 逐字节透传到 Java 工具调用（X-Request-Id + Authorization）
    tool_request = handler.requests[1]
    assert tool_request.url.path == "/api/agent/tools/query-stock-level"
    assert tool_request.headers["x-request-id"] == request_id
    assert tool_request.headers["authorization"] == DEFAULT_AUTH

    assert generated.headers["x-request-id"].startswith("req-")
    assert generated.json()["data"]["request_id"] == generated.headers["x-request-id"]

    assert illegal.status_code == 400
    assert "x-request-id" not in illegal.headers  # 非法 id 不回写
    assert illegal.json()["code"] == 400
    assert len(handler.requests) == before  # 中间件在路由前拒绝，不触达 Java


def test_success_envelope_shape_sources_omission_and_conversation_echo():
    handler = RecordingJava({"query-stock-level": {"code": 0, "message": "ok", "data": STOCK_DATA}})
    app = build_app(FakeModelProvider(), handler)

    with TestClient(app) as client:
        echoed = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？", "conversation_id": "conv-final-chain-01"},
            headers={"Authorization": DEFAULT_AUTH},
        ).json()
        generated = client.post(
            "/agent/chat",
            json={"message": "AUD-001 库存？"},
            headers={"Authorization": DEFAULT_AUTH},
        ).json()

    assert set(echoed.keys()) == {"code", "message", "data"}
    assert echoed["code"] == 0 and echoed["message"] == "ok"
    data = echoed["data"]
    assert set(data.keys()) == {"request_id", "conversation_id", "reply", "tool_calls"}  # 无命中省略 sources
    assert data["conversation_id"] == "conv-final-chain-01"
    assert data["reply"] == "AUD-001（无线耳机）当前库存 18，档位 low，快照 2026-09-03"
    assert len(data["tool_calls"]) == 1
    record = data["tool_calls"][0]
    assert set(record.keys()) == {
        "tool_call_id",
        "tool",
        "params",
        "ok",
        "round",
        "summary",
        "error",
    }
    assert record["tool"] == "query-stock-level"
    assert record["ok"] is True and record["round"] == 1 and record["error"] is None
    assert generated["data"]["conversation_id"].startswith("conv-")
    assert generated["data"]["conversation_id"] != "conv-final-chain-01"


# ================================================================= B. Harness 语义


def test_stop_semantics_answered_and_tool_assembled_map_to_ok():
    data, run, client = run_chat(ScriptedProvider([ModelResponse(text="直接回答，不调工具。")]))
    assert data.reply == "直接回答，不调工具。"
    assert client.calls == []
    assert run.stop_reason == STOP_ANSWERED and run.status == "ok"

    data, run, client = run_chat(FakeModelProvider())
    assert client.calls == ["query-stock-level"]
    assert run.stop_reason == STOP_TOOL_ASSEMBLED and run.status == "ok"
    assert run.tool_call_count == 1 and run.model_tools_count == 3


def test_round_limit_is_degraded_with_reply_suffix():
    provider = ScriptedProvider(
        [
            ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="query-stock-level", params={"skus": ["AUD-001"]}, call_id=f"c{i}"
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            )
            for i in range(2)
        ]
    )
    data, run, client = run_chat(provider, max_rounds=2, message="AUD-001 库存与销量")

    assert provider.calls == 2 and client.calls == ["query-stock-level", "query-stock-level"]
    assert run.stop_reason == STOP_ROUND_LIMIT and run.status == "degraded"
    assert data.reply.endswith(TOOL_CALL_SUFFIX)
    assert "AUD-001（无线耳机）当前库存 18" in data.reply


def test_model_error_is_degraded_with_fixed_reply():
    class FailingProvider(ModelProvider):
        async def complete(self, messages, tools=None):
            raise ModelProviderError()

    data, run, client = run_chat(FailingProvider())
    assert data.reply == MODEL_FAILURE_REPLY
    assert data.tool_calls == []
    assert client.calls == []
    assert run.stop_reason == STOP_MODEL_ERROR and run.status == "degraded"


def test_zero_budget_is_degraded_without_model_or_tool_calls():
    provider = ScriptedProvider([ModelResponse(text="不该出现")])
    data, run, client = run_chat(provider, budget=0.0)

    assert data.reply == SCHEMA_FAILURE_REPLY
    assert provider.calls == 0 and client.calls == []
    assert run.stop_reason == STOP_BUDGET_EXHAUSTED and run.status == "degraded"


# ================================================================== C. 安全契约


def test_model_visible_tools_are_read_only_subset_of_contract():
    snapshot = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
    expected = sorted(
        tool["name"]
        for tool in snapshot["tools"]
        if tool.get("readOnly") and not tool.get("requiresApproval")
    )
    assert len(expected) == 3

    handler = RecordingJava({"query-stock-level": {"code": 0, "message": "ok", "data": STOCK_DATA}})
    provider = RecordingProvider()
    app = build_app(provider, handler)

    body = post_chat(app, {"message": "AUD-001 库存？"}).json()
    assert body["code"] == 0

    messages, tools = provider.calls[0]
    assert messages[0] == {"role": "system", "content": SYSTEM_PROMPT}
    names = sorted(tool["name"] for tool in tools)
    assert names == expected
    assert "create-purchase-order" not in names and "confirm-po" not in names
    assert all(tool.get("readOnly") is True for tool in tools)


def test_write_tool_hallucination_rejected_and_never_touches_endpoint():
    provider = ScriptedProvider(
        [
            ModelResponse(
                tool_calls=[
                    ToolCallRequest(
                        name="create-purchase-order",
                        params={"items": [{"sku": "AUD-001", "qty": 48}]},
                        call_id="call-w1",
                    )
                ],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(text="该操作不会自动执行。"),
        ]
    )
    handler = RecordingJava()
    app = build_app(provider, handler)

    body = post_chat(app, {"message": "帮我下单 AUD-001 补 48 件"}).json()

    assert body["code"] == 0
    record = body["data"]["tool_calls"][0]
    assert record["tool"] == "create-purchase-order"
    assert record["ok"] is False
    assert record["error"] == "该工具需要人工确认，当前未执行。"
    assert body["data"]["reply"] == "该操作不会自动执行。"
    assert handler.paths == ["/api/agent/tools/schema"]  # 写端点零触达


def test_unknown_tool_rejected_and_never_touches_endpoint():
    provider = ScriptedProvider(
        [
            ModelResponse(
                tool_calls=[ToolCallRequest(name="update-stock", params={"sku": "AUD-001"})],
                assistant_message={"role": "assistant", "content": None},
            ),
            ModelResponse(text="无法执行该操作。"),
        ]
    )
    handler = RecordingJava()
    app = build_app(provider, handler)

    body = post_chat(app, {"message": "把库存改成 999 件"}).json()

    record = body["data"]["tool_calls"][0]
    assert record["ok"] is False
    assert record["error"] == "未知工具：update-stock"
    assert body["data"]["reply"] == "无法执行该操作。"
    assert handler.paths == ["/api/agent/tools/schema"]


# ================================================================== D. W4 闭环


def test_full_chain_history_order_reference_and_sources(tmp_path):
    knowledge = tmp_path / "knowledge"
    knowledge.mkdir()
    (knowledge / "supply-ops.zh.md").write_text(KNOWLEDGE_DOC, encoding="utf-8")

    handler = RecordingJava()
    provider = RecordingProvider()
    app = build_app(provider, handler, retriever=KnowledgeRetrieverV2(knowledge))

    query = "采购单需要人工确认吗？"
    data = post_chat(app, {"message": query, "history": HISTORY}).json()["data"]

    messages = provider.calls[0][0]
    assert len(messages) == 5
    assert messages[0] == {"role": "system", "content": SYSTEM_PROMPT}
    assert messages[1] == HISTORY[0]
    assert messages[2] == HISTORY[1]
    reference = messages[3]
    assert reference["role"] == "user"
    assert reference["content"].startswith(REFERENCE_HEADER)
    assert REFERENCE_FOOTER in reference["content"]
    assert messages[4] == {"role": "user", "content": query}

    sources = data["sources"]
    assert sources and len(sources) == 1
    assert set(sources[0].keys()) == {"doc", "section", "chunk_id"}
    assert sources[0]["section"] == "采购单与人工确认"  # section = chunk 标题
    assert sources[0]["chunk_id"].startswith("supply-ops.zh#purchase-approval")
    # sources 只由实际注入 chunk 生成：引用消息里出现的 chunk_id 与响应 sources 一致
    assert sources[0]["chunk_id"] in reference["content"]


def test_full_chain_absent_or_empty_history_keeps_legacy_messages():
    handler = RecordingJava()
    provider = RecordingProvider()
    app = build_app(provider, handler)

    with TestClient(app) as client:
        client.post(
            "/agent/chat",
            json={"message": "hi"},
            headers={"Authorization": DEFAULT_AUTH},
        )
        client.post(
            "/agent/chat",
            json={"message": "hi", "history": []},
            headers={"Authorization": DEFAULT_AUTH},
        )

    legacy = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "hi"},
    ]
    assert provider.calls[0][0] == legacy
    assert provider.calls[1][0] == legacy


def test_full_chain_trace_record_for_http_run_is_desensitized(tmp_path):
    trace_file = tmp_path / "final-trace.jsonl"
    handler = RecordingJava({"query-stock-level": {"code": 0, "message": "ok", "data": STOCK_DATA}})
    app = build_app(FakeModelProvider(), handler, trace_file=trace_file)

    request_id = "req-trace-contract-1"
    response = post_chat(app, {"message": "AUD-001 库存？"}, request_id=request_id)
    assert response.status_code == 200

    lines = trace_file.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["trace_version"] == 1

    run = record["run"]
    assert run["request_id"] == request_id
    assert re.fullmatch(r"[0-9a-f]{32}", run["run_key"])
    assert run["stop_reason"] == STOP_TOOL_ASSEMBLED and run["status"] == "ok"
    assert run["rounds_used"] == 1 and run["tool_calls"] == 1 and run["model_tools"] == 3
    assert [event["type"] for event in record["events"]] == [
        "schema_load",
        "rag_retrieve",
        "model_call",
        "tool_call",
        "response_assemble",
    ]

    raw = lines[0]
    assert DEFAULT_AUTH not in raw  # 凭证不入 Trace
    assert "AUD-001" not in raw  # 用户消息与工具参数值不入 Trace
    assert "无线耳机" not in raw  # 工具返回正文不入 Trace
