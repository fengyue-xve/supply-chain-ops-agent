"""Agent Gateway 入口与应用工厂。

create_app(java_client/provider/schema_loader/retriever=None) 支持测试整体注入，
不注入时按 Settings（环境变量）自建，指向本机 Java、仓库契约快照与知识目录。
uvicorn 启动：fastapi-service 下 `uvicorn app.main:app --port 8000`。
"""

from contextlib import asynccontextmanager
import inspect
import os
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.chat import router as chat_router
from app.agent.events import build_event_sink
from app.agent.orchestrator import AgentOrchestrator
from app.config import Settings, load_settings
from app.errors import ApiError
from app.logging_middleware import RequestIdMiddleware
from app.models.base import ModelProvider
from app.models.deepseek import DeepSeekProvider
from app.models.fake import FakeModelProvider
from app.rag.retriever import KnowledgeRetriever
from app.rag.retriever_v3 import KnowledgeRetrieverV3
from app.schemas import ApiResponse
from app.tools.java_client import JavaToolClient
from app.tools.schema_loader import ToolSchemaLoader


def create_app(
    settings: Settings | None = None,
    java_client: JavaToolClient | None = None,
    provider: ModelProvider | None = None,
    schema_loader: ToolSchemaLoader | None = None,
    retriever: KnowledgeRetriever | None = None,
    trace_file: Path | str | None = None,
) -> FastAPI:
    settings = settings or load_settings()
    client = java_client or JavaToolClient(
        base_url=settings.java_base_url,
        timeout_seconds=settings.java_timeout_seconds,
        health_timeout_seconds=settings.java_health_timeout_seconds,
    )
    if provider is None:
        mode = os.getenv("AGENT_PROVIDER", "deepseek")
        if mode == "demo":
            provider = FakeModelProvider()
        elif mode == "deepseek":
            provider = DeepSeekProvider(
                api_key=settings.model_api_key,
                base_url=settings.model_base_url,
                model=settings.model_name,
                timeout_seconds=settings.model_timeout_seconds,
                temperature=settings.model_temperature,
            )
        else:
            raise ValueError("AGENT_PROVIDER must be demo or deepseek")
    loader = schema_loader or ToolSchemaLoader(
        client, settings.tools_schema_path, settings.schema_cache_ttl_seconds
    )
    retriever = retriever or KnowledgeRetrieverV3(settings.knowledge_dir)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        await client.aclose()
        if hasattr(provider, "aclose"):
            result = provider.aclose()
            if inspect.isawaitable(result):
                await result

    app = FastAPI(
        title="供应链智能运营 Agent Gateway",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.add_middleware(RequestIdMiddleware)

    event_sink = build_event_sink(trace_file or settings.agent_trace_file)

    app.state.settings = settings
    app.state.java_client = client
    app.state.provider = provider
    app.state.schema_loader = loader
    app.state.retriever = retriever
    app.state.event_sink = event_sink
    app.state.orchestrator = AgentOrchestrator(
        provider,
        client,
        loader,
        retriever,
        max_rounds=settings.agent_max_rounds,
        total_budget_seconds=settings.agent_total_budget_seconds,
        event_sink=event_sink,
    )

    app.include_router(chat_router)
    _register_error_handlers(app)
    return app


def _register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.code,
            content=ApiResponse.fail(exc.code, exc.message).model_dump(),
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=ApiResponse.fail(400, "请求体格式错误").model_dump(),
        )


app = create_app()
