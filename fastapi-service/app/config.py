"""运行配置：环境变量可覆盖，缺省指向本机 Java(8080) 与仓库根契约快照。

AGENT_TOTAL_BUDGET_SECONDS 非法（非数字、<=0 或不小于 Java 网关上游超时）时
load_settings() 显式报错，服务启动即失败，不静默放行错误保证。
"""

import os
from dataclasses import dataclass
from pathlib import Path

from app.rag.documents import DEFAULT_KNOWLEDGE_DIR

# app/config.py -> parents[0]=app、[1]=fastapi-service、[2]=项目根
_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_SCHEMA_PATH = _PROJECT_ROOT / "contracts" / "tools-schema.json"

# Java 网关对 FastAPI 的上游超时（application.yml: app.agent-gateway.timeout-seconds，默认 30s）。
# 总预算必须严格小于它，才能保证本层先于 Java 504 返回降级结果。
JAVA_GATEWAY_UPSTREAM_TIMEOUT_SECONDS = 30.0
DEFAULT_AGENT_TOTAL_BUDGET_SECONDS = 25.0


@dataclass(frozen=True)
class Settings:
    java_base_url: str
    java_timeout_seconds: float
    java_health_timeout_seconds: float
    tools_schema_path: Path
    schema_cache_ttl_seconds: float
    model_api_key: str
    model_base_url: str
    model_name: str
    model_timeout_seconds: float
    model_temperature: float
    agent_max_rounds: int
    agent_total_budget_seconds: float
    knowledge_dir: Path
    agent_trace_file: Path | None


def load_settings() -> Settings:
    return Settings(
        java_base_url=os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080"),
        java_timeout_seconds=float(os.getenv("JAVA_TIMEOUT_SECONDS", "3.0")),
        java_health_timeout_seconds=float(os.getenv("JAVA_HEALTH_TIMEOUT_SECONDS", "1.0")),
        tools_schema_path=Path(os.getenv("TOOLS_SCHEMA_PATH", str(_DEFAULT_SCHEMA_PATH))),
        schema_cache_ttl_seconds=float(os.getenv("SCHEMA_CACHE_TTL_SECONDS", "60.0")),
        model_api_key=os.getenv("DEEPSEEK_API_KEY", ""),
        model_base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
        model_name=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        model_timeout_seconds=float(os.getenv("DEEPSEEK_TIMEOUT_SECONDS", "30.0")),
        model_temperature=float(os.getenv("DEEPSEEK_TEMPERATURE", "0.3")),
        agent_max_rounds=int(os.getenv("AGENT_MAX_ROUNDS", "5")),
        # 端到端总预算：0 < 预算 < Java 网关上游超时；非法值显式报错（见 _parse_total_budget）。
        agent_total_budget_seconds=_parse_total_budget(),
        knowledge_dir=Path(os.getenv("KNOWLEDGE_DIR", str(DEFAULT_KNOWLEDGE_DIR))),
        # Trace 默认关闭：AGENT_TRACE_FILE 设为文件路径（如 logs/agent-trace.jsonl）才开启；
        # 空值/未设置 → None（不创建任何文件）。CLI：python -m app.trace_cli --request-id ...
        agent_trace_file=_parse_trace_file(),
    )


def _parse_trace_file() -> Path | None:
    raw = os.getenv("AGENT_TRACE_FILE", "").strip()
    return Path(raw) if raw else None


def _parse_total_budget() -> float:
    """非法配置显式失败：不静默放大/回退，避免产生「预算 < 网关超时」之外的错误保证。"""
    raw = os.getenv(
        "AGENT_TOTAL_BUDGET_SECONDS", str(DEFAULT_AGENT_TOTAL_BUDGET_SECONDS)
    )
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"AGENT_TOTAL_BUDGET_SECONDS 非法：{raw!r} 不是数字") from exc
    if not 0.0 < value < JAVA_GATEWAY_UPSTREAM_TIMEOUT_SECONDS:
        raise ValueError(
            f"AGENT_TOTAL_BUDGET_SECONDS 非法：{value}；"
            f"必须满足 0 < 预算 < {JAVA_GATEWAY_UPSTREAM_TIMEOUT_SECONDS}"
            "（Java 网关上游超时），否则无法保证本层先于 Java 返回降级结果"
        )
    return value
