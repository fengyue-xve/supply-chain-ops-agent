"""评测入口：默认离线 replay（不触网、不需要任何真实凭据），--live 用真实 DeepSeek。

套件（--suite）：
- agent-baseline.v1（默认）：RAG 前真实 B0 基线。replay 注入空检索器，消息与 RAG 接入
  前逐字节一致（协议与编排回归）；live 启用真实知识检索，供 RAG 接入后与冻结 B0 对照复跑。
- knowledge.v1：RAG 业务知识检索（B0 用例集之外独立文件/独立报告/独立指标，不混合计算）。
  仅支持 replay（手工夹具响应）：验证注入协议与引用契约（sources 必须来自实际检索、
  chunk_id 真实存在、无命中不得返回 sources、伪造引用为 0）；groundedness 标记 judged
  转人工复核，需真实模型输出后评审。
- retrieval.v1：纯检索层确定性评测（无模型、无 Java、无网络、无 cassette）。18 条独立用例
  （hit/multi/no-hit/boundary）评价「检索 → 阈值 → top-k → 注入预算 → sources」整段：
  期望 chunk 全注入、无命中零注入、两次检索顺序稳定、sources 与实际注入一致为硬门禁；
  Recall@K 为逐条来源指标，MRR/Top-1 仅为排序诊断。仅支持离线（--live 拒绝），独立报告
  结构（report_schema_version=1，retrieval 专属），不与 agent-baseline/knowledge 混算。
- retrieval.v2：v2 检索器召回/噪声评测（同样纯离线）。20 条独立用例（口语/中英混排/单短词/
  SKU+实时数值/跨段组合/无关/近似负样本），人工标签分 expected（必须注入）/allowed（可容忍
  邻接）/forbidden（出现即失败）三类且覆盖全语料；硬门禁新增 forbidden 零注入，Precision@K
  为诊断。使用生产默认的 v2 检索器（v1 行为冻结不变），报告 report_schema_version=2。
- retrieval.shadow：v1/v2 检索器对照基准（同样纯离线）。同一批人工写定查询（独立资产
  cases/retrieval.shadow.v1.jsonl）逐条分别跑 v1（冻结对照）与 v2（生产默认），输出并列的
  受控词法回归指标（expected recall/覆盖、Top-1、forbidden 注入、no-hit clean、注入与额外
  chunk 数、allowed_injection_rate）。只做测量，不设单侧质量门禁，不修改 retrieval.v1/v2
  的用例与断言；报告 report_schema_version=3。

replay：Java 行为由 JavaStub 承担，模型行为由 cassettes/<suite>/<case_id>.json 按序回放
（经真实 DeepSeekProvider 解析路径），逐条用例独立运行（每条消息不依赖历史会话）。

live：真实模型 + live 期望覆盖（cases/agent-baseline.v1.live.json）。不要求复刻 cassette
调用序列：required 必调、allowed 可多调（计入效率）、forbidden 绝不可调；strict_plan_match
仅作诊断。judged 用例转人工复核，不阻塞硬门禁。live 结果定性为 B0 校准运行，不是最终
模型质量成绩，也不覆盖 replay cassette。

退出码：0 = 全部通过；1 = 存在失败用例；2 = 用法/配置错误（如 --live 缺 key）；
3 = 报告敏感扫描命中（拒绝写出报告）。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

from fastapi.testclient import TestClient

from app.agent.context import source_refs
from app.main import create_app
from app.models.deepseek import DeepSeekProvider
from app.rag.retriever import (
    MIN_SCORE,
    PROMPT_BUDGET_CHARS,
    TOP_K,
    EmptyRetriever,
    KnowledgeRetriever,
    render_chunk,
    select_for_prompt,
)
from app.rag.retriever_v2 import KnowledgeRetrieverV2
from app.tools.schema_loader import ToolSchemaLoader
from evals.metrics import (
    RETRIEVAL_CHECK_NAMES,
    RETRIEVAL_V2_CHECK_NAMES,
    SEMANTIC_CHECK_NAMES,
    _knowledge_summary,
    aggregate,
    aggregate_knowledge,
    aggregate_live,
    aggregate_retrieval,
    aggregate_retrieval_shadow,
    aggregate_retrieval_v2,
    evaluate_case,
    evaluate_case_live,
    evaluate_knowledge_case,
    evaluate_retrieval_case,
    evaluate_retrieval_v2_case,
    injection_relevance,
    live_status,
    recall_at_k,
    reciprocal_rank,
    scan_for_secrets,
    shadow_empty_summary,
    shadow_side_checks,
    shadow_side_summary,
    unnecessary_readonly_calls,
    write_safety_counts,
)
from evals.providers import CapturingTransport, load_cassette
from evals.support import (
    BASELINE_SUITE,
    FROZEN_KNOWLEDGE_DIR,
    CASSETTE_DIR,
    CASES_PATH,
    CATEGORIES,
    CONTRACTS_SNAPSHOT,
    KNOWLEDGE_CASSETTE_DIR,
    KNOWLEDGE_CASES_PATH,
    KNOWLEDGE_SUITE,
    LIVE_CASES_PATH,
    PROJECT_ROOT,
    REPORTS_DIR,
    READONLY_TOOL_NAMES,
    RETRIEVAL_CASES_PATH,
    RETRIEVAL_CATEGORIES,
    RETRIEVAL_SHADOW_CASES_PATH,
    RETRIEVAL_SHADOW_CATEGORIES,
    RETRIEVAL_SHADOW_SUITE,
    RETRIEVAL_SUITE,
    RETRIEVAL_V2_CASES_PATH,
    RETRIEVAL_V2_CATEGORIES,
    RETRIEVAL_V2_SUITE,
    SUITE_CHOICES,
    WRITE_TOOL_NAMES,
    JavaStub,
    live_payloads,
    load_cases,
    load_knowledge_cases,
    load_live_overlay,
    load_retrieval_cases,
    load_retrieval_shadow_cases,
    load_retrieval_v2_cases,
    resolve_fixture,
    resolve_request_id,
    validate_cases,
    validate_knowledge_cases,
    validate_live_overlay,
    validate_retrieval_cases,
    validate_retrieval_shadow_cases,
    validate_retrieval_v2_cases,
)

EVAL_AUTHORIZATION = "Bearer eval-local-placeholder"
REPLAY_API_KEY = "eval-local-placeholder-key"
LIVE_MODEL = "deepseek-chat"
LIVE_TEMPERATURE = 0.3


def _git_head() -> str | None:
    try:
        output = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            cwd=PROJECT_ROOT,
            timeout=5,
        )
    except Exception:
        return None
    return output.stdout.strip() or None


def _build_retriever(suite: str, mode: str):
    """replay 基线注入空检索器（与 RAG 接入前逐字节一致）；knowledge/retrieval 与 live 用真实检索。

    retrieval.v2 / retrieval.shadow 用生产默认的 KnowledgeRetrieverV2；knowledge/retrieval.v1/live
    固定用 v1 检索器（v1 输出是 retrieval.v1 与 knowledge.v1 冻结期望的依据，不随生产默认变化）。
    retrieval.shadow 套件内部再单独构建 v1 与 v2 两侧（见 _run_retrieval_shadow_suite）。
    """
    if suite in (RETRIEVAL_V2_SUITE, RETRIEVAL_SHADOW_SUITE):
        return KnowledgeRetrieverV2(os.environ.get("KNOWLEDGE_DIR") or FROZEN_KNOWLEDGE_DIR)
    if suite in (KNOWLEDGE_SUITE, RETRIEVAL_SUITE) or mode == "live":
        return KnowledgeRetriever(os.environ.get("KNOWLEDGE_DIR") or (FROZEN_KNOWLEDGE_DIR if mode != "live" else None))
    return EmptyRetriever()


def run_case(
    case: dict, mode: str, expectation: dict | None = None, suite: str = BASELINE_SUITE
) -> dict:
    if mode == "live" and expectation is None:
        raise ValueError("live 模式必须提供 live 期望覆盖（cases/agent-baseline.v1.live.json）")
    if suite == KNOWLEDGE_SUITE and mode != "replay":
        raise ValueError("knowledge.v1 仅支持 replay（groundedness 为 judged，live 需后续单独流程）")
    started = time.perf_counter()
    case["_request_id"] = resolve_request_id(case)
    observation: dict = {"mode": mode}
    retriever = _build_retriever(suite, mode)
    if suite == KNOWLEDGE_SUITE:
        # 引用契约的期望侧：同一条检索链路重算实际应注入的 chunk（纯函数、结果确定）
        expected_chunks = select_for_prompt(
            retriever.search(case["user_message"].strip(), request_id=case["_request_id"])
        )
        observation["expected_source_ids"] = [chunk.chunk_id for chunk in expected_chunks]
        observation["knowledge_chunk_ids"] = [chunk.chunk_id for chunk in retriever.chunks]
    client = None
    provider = None
    capture = None
    cassette = None
    stub = JavaStub(
        payloads=live_payloads(case) if mode == "live" else resolve_fixture(case),
        tool_errors=case.get("java_tool_errors"),
        down=bool(case.get("java_down")),
    )
    try:
        client = stub.build_client()
        loader = ToolSchemaLoader(client, snapshot_path=CONTRACTS_SNAPSHOT, ttl_seconds=600.0)
        if mode == "replay":
            cassette_dir = KNOWLEDGE_CASSETTE_DIR if suite == KNOWLEDGE_SUITE else CASSETTE_DIR
            cassette = load_cassette(
                cassette_dir / f"{case['case_id']}.json", case_id=case["case_id"]
            )
            capture = CapturingTransport(cassette.transport())
            provider = DeepSeekProvider(api_key=REPLAY_API_KEY, transport=capture)
        else:
            capture = CapturingTransport()
            provider = DeepSeekProvider(
                api_key=os.environ["DEEPSEEK_API_KEY"],
                model=LIVE_MODEL,
                temperature=LIVE_TEMPERATURE,
                transport=capture,
            )
        app = create_app(
            java_client=client, provider=provider, schema_loader=loader, retriever=retriever
        )
        payload: dict = {"message": case["user_message"]}
        if case.get("conversation_id"):
            payload["conversation_id"] = case["conversation_id"]
        headers = {"Authorization": EVAL_AUTHORIZATION, "X-Request-Id": case["_request_id"]}
        with TestClient(app) as test_client:
            response = test_client.post("/agent/chat", json=payload, headers=headers)
        observation["status_code"] = response.status_code
        try:
            observation["body"] = response.json()
        except ValueError:
            observation["body"] = None
        observation["headers"] = {key.lower(): value for key, value in response.headers.items()}
        observation["schema_source"] = loader.source
    except Exception as exc:  # 用例级捕获：单条异常不中止整轮评测
        observation["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        observation["stub"] = stub
        observation["capture_requests"] = list(capture.requests) if capture else []
        observation["cassette_remaining"] = cassette.remaining if cassette else None
        _best_effort_aclose(client, provider)

    if mode == "live":
        checks = evaluate_case_live(case, expectation, observation)
        status = live_status(checks, bool(expectation["judged"]))
        summary = _live_summary(case, expectation, observation, checks)
    elif suite == KNOWLEDGE_SUITE:
        checks = evaluate_knowledge_case(case, observation)
        failed = [check for check in checks if check["ok"] is False]
        status = "passed" if not failed else "failed"
        summary = _knowledge_summary(case, observation)
    else:
        checks = evaluate_case(case, observation)
        failed = [check for check in checks if check["ok"] is False]
        status = "passed" if not failed else "failed"
        summary = _replay_summary(case, observation)
    return {
        "case_id": case["case_id"],
        "category": case["category"],
        "title": case.get("title", ""),
        "status": status,
        "duration_ms": round((time.perf_counter() - started) * 1000),
        "checks": checks,
        "trace": _build_trace(observation, mode),
        "summary": summary,
    }


def _replay_summary(case: dict, observation: dict) -> dict:
    body = observation.get("body")
    data = body.get("data") if isinstance(body, dict) and isinstance(body.get("data"), dict) else {}
    records = [item for item in (data.get("tool_calls") or []) if isinstance(item, dict)]
    called = [record.get("tool") for record in records]
    safety = write_safety_counts(observation)
    return {
        "expected_tool_count": len(case["expected_tool_names"]),
        "strict_plan_match": called == case["expected_tool_names"],
        "has_param_expectations": bool(case["expected_params"]),
        "has_chinese": bool(case.get("expected_chinese")),
        "requires_real_model": bool(case["requires_real_model"]),
        "judged": case["deterministic_or_judged"] == "judged",
        "visible_write_tools": safety["visible_write_tools"],
        "write_tool_attempts": safety["write_tool_attempts"],
        "write_tool_endpoint_touches": safety["write_tool_endpoint_touches"],
        "write_tool_executions": safety["write_tool_executions"],
        "unknown_tool_attempts": safety["unknown_tool_attempts"],
        "rejected_write_tool_attempt": safety["rejected_write_tool_attempt"],
    }


def _live_summary(case: dict, expectation: dict, observation: dict, checks: list[dict]) -> dict:
    body = observation.get("body")
    data = body.get("data") if isinstance(body, dict) and isinstance(body.get("data"), dict) else {}
    records = [item for item in (data.get("tool_calls") or []) if isinstance(item, dict)]
    safety = write_safety_counts(observation)
    required = expectation["required_tools"]
    called = [record.get("tool") for record in records]
    missing = [tool for tool in required if tool not in called]
    chinese = (
        expectation["expected_chinese"]
        if "expected_chinese" in expectation
        else case.get("expected_chinese")
    )
    semantic_failures = [
        check["name"]
        for check in checks
        if check["ok"] is False and check["name"] in SEMANTIC_CHECK_NAMES
    ]
    return {
        "expected_tool_count": len(case["expected_tool_names"]),
        "required_tool_count": len(required),
        "required_tools_missing": missing,
        "has_param_specs": bool(expectation.get("params")),
        "has_chinese": bool(chinese),
        "strict_plan_match": called == case["expected_tool_names"],
        "unnecessary_tool_calls": unnecessary_readonly_calls(required, records),
        "requires_real_model": bool(case["requires_real_model"]),
        "judged": bool(expectation["judged"]),
        "semantic_failures": semantic_failures,
        "visible_write_tools": safety["visible_write_tools"],
        "write_tool_attempts": safety["write_tool_attempts"],
        "write_tool_endpoint_touches": safety["write_tool_endpoint_touches"],
        "write_tool_executions": safety["write_tool_executions"],
        "unknown_tool_attempts": safety["unknown_tool_attempts"],
        "rejected_write_tool_attempt": safety["rejected_write_tool_attempt"],
    }


REPLY_PREVIEW_CHARS = 200
REPLY_FULL_CHARS = 4000


def _build_trace(observation: dict, mode: str = "replay") -> dict:
    body = observation.get("body")
    data = body.get("data") if isinstance(body, dict) and isinstance(body.get("data"), dict) else {}
    stub = observation.get("stub")
    records = data.get("tool_calls") if isinstance(data.get("tool_calls"), list) else []
    trace = {
        "request_id": data.get("request_id"),
        "conversation_id": data.get("conversation_id"),
        "source_refs": data.get("sources"),
        "reply_preview": (data.get("reply") or "")[:REPLY_PREVIEW_CHARS],
        "tool_calls": [
            {
                "tool": record.get("tool"),
                "ok": record.get("ok"),
                "round": record.get("round"),
                "error": (record.get("error") or "")[:120] or None,
            }
            for record in records
            if isinstance(record, dict)
        ],
        "java_paths": stub.tool_paths if stub else [],
        "model_requests": observation.get("capture_requests") or [],
        "cassette_remaining": observation.get("cassette_remaining"),
        "schema_source": observation.get("schema_source"),
        "error": (observation.get("error") or "")[:300] or None,
    }
    if mode == "live":
        # 完整回复供 judged 人工复核；replay 报告保持原格式不动
        reply = data.get("reply") or ""
        trace["reply"] = reply[:REPLY_FULL_CHARS]
        trace["reply_truncated"] = len(reply) > REPLY_FULL_CHARS
    return trace


def _best_effort_aclose(*objects) -> None:
    for obj in objects:
        if obj is None or not hasattr(obj, "aclose"):
            continue
        try:
            asyncio.run(obj.aclose())
        except Exception:
            pass


def _crash_result(case: dict, exc: Exception) -> dict:
    return {
        "case_id": case.get("case_id", "<未知>"),
        "category": case.get("category", "<未知>"),
        "title": case.get("title", ""),
        "status": "failed",
        "duration_ms": 0,
        "checks": [
            {
                "name": "completed",
                "ok": False,
                "detail": f"用例执行异常：{type(exc).__name__}: {exc}",
            }
        ],
        "trace": {},
        "summary": {
            "expected_tool_count": len(case.get("expected_tool_names") or []),
            "has_param_expectations": bool(case.get("expected_params")),
            "has_chinese": bool(case.get("expected_chinese")),
            "requires_real_model": bool(case.get("requires_real_model")),
            "judged": case.get("deterministic_or_judged") == "judged",
            "visible_write_tools": 0,
            "write_tool_attempts": 0,
            "write_tool_endpoint_touches": 0,
            "write_tool_executions": 0,
            "unknown_tool_attempts": 0,
            "rejected_write_tool_attempt": False,
            "required_tool_count": 0,
            "required_tools_missing": [],
            "has_param_specs": False,
            "strict_plan_match": False,
            "unnecessary_tool_calls": 0,
            "semantic_failures": [],
        },
    }


def _build_report(
    results: list[dict], metrics: dict, mode: str, suite: str = BASELINE_SUITE
) -> dict:
    cases_path = KNOWLEDGE_CASES_PATH if suite == KNOWLEDGE_SUITE else CASES_PATH
    report = {
        "meta": {
            "report_schema_version": 2,
            "suite": suite,
            "mode": mode,
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "base_commit": _git_head(),
            "model": LIVE_MODEL if mode == "live" else "cassette-replay",
            "temperature": LIVE_TEMPERATURE if mode == "live" else None,
            "api_calls": sum(len(result["trace"].get("model_requests") or []) for result in results),
            "total_duration_ms": sum(result["duration_ms"] for result in results),
            "cases_file": str(cases_path.relative_to(PROJECT_ROOT)),
            "expectation_source": (
                str(LIVE_CASES_PATH.relative_to(PROJECT_ROOT)) if mode == "live" else "replay-cassette"
            ),
            "case_count": len(results),
            "tool_boundary": {
                "model_visible_readonly": list(READONLY_TOOL_NAMES),
                "hidden_write": list(WRITE_TOOL_NAMES),
            },
            "retrieval": {
                "enabled": suite == KNOWLEDGE_SUITE or mode == "live",
                "top_k": TOP_K,
                "min_score": MIN_SCORE,
                "budget_chars": PROMPT_BUDGET_CHARS,
            },
        },
        "metrics": metrics,
        "cases": results,
        "failures": [
            {
                "case_id": result["case_id"],
                "category": result["category"],
                "failed_checks": [
                    {"name": check["name"], "detail": check["detail"], "token": check.get("token")}
                    for check in result["checks"]
                    if check["ok"] is False
                ],
                "trace": result["trace"],
            }
            for result in results
            if result["status"] == "failed"
        ],
    }
    if mode == "live":
        report["judged_review"] = [
            {
                "case_id": result["case_id"],
                "category": result["category"],
                "semantic_failures": result["summary"].get("semantic_failures") or [],
                "trace": result["trace"],
            }
            for result in results
            if result["summary"].get("judged")
        ]
    if suite == KNOWLEDGE_SUITE:
        corpus = Path(os.environ.get("KNOWLEDGE_DIR") or FROZEN_KNOWLEDGE_DIR)
        try:
            corpus_dir = str(corpus.resolve().relative_to(PROJECT_ROOT.resolve()))
        except ValueError:
            corpus_dir = str(corpus)
        report["meta"]["corpus_dir"] = corpus_dir
        report["meta"]["corpus_scope"] = "explicit_override" if os.environ.get("KNOWLEDGE_DIR") else "historical_pre_business_20261020"
        report["meta"]["current_business_quality_verified"] = False
        report["meta"]["groundedness"] = "judged"
        report["meta"]["replay_note"] = (
            "replay 响应为手工夹具：仅验证注入协议与引用契约，不代表真实模型质量；"
            "groundedness（回答是否被注入文档支撑）需对真实模型输出人工复核"
        )
        report["groundedness_review"] = [
            {
                "case_id": result["case_id"],
                "injected_source_ids": result["summary"].get("expected_source_ids") or [],
                "reasons": ["groundedness"],
            }
            for result in results
        ]
    return report


def _leaked_reply_cases(results: list[dict]) -> list[str]:
    """完整回复的敏感扫描：命中即拒绝写出报告（评测红线，与整份报告扫描双保险）。"""
    return [
        result["case_id"]
        for result in results
        if scan_for_secrets(result["trace"].get("reply") or "")
    ]


def _print_summary(metrics: dict, mode: str = "replay") -> None:
    def percent(key: str) -> str:
        value = metrics[key]
        return "n/a" if value is None else f"{value * 100:.1f}%"

    print("-" * 72)
    if mode == "live":
        evaluated = metrics["total_cases"] - metrics["judged_cases"]
        print(
            f"通过 {metrics['passed_cases']} / {evaluated}，失败 {metrics['failed_cases']}，"
            f"judged {metrics['judged_cases']} 条转人工复核"
        )
        print(
            f"指标：任务成功 {percent('task_success_rate')} | "
            f"必需工具召回 {percent('required_tool_recall')} | "
            f"参数合法 {percent('parameter_validity')} | "
            f"澄清 {percent('clarification_accuracy')} | "
            f"信封结构 {percent('response_schema_validity')} | "
            f"中文字段 {percent('chinese_field_accuracy')} | "
            f"语言一致 {percent('language_consistency_rate')}"
        )
        print(
            f"诊断（不判通过）：严格计划一致 {percent('strict_plan_match_rate')}；"
            f"多余只读调用 {metrics['unnecessary_tool_calls']} 次"
            f"（涉及 {len(metrics['unnecessary_tool_call_cases'])} 条用例）"
        )
    else:
        print(
            f"通过 {metrics['passed_cases']} / {metrics['total_cases']}，"
            f"失败 {metrics['failed_cases']}"
        )
        print(
            f"指标：严格计划一致 {percent('strict_plan_match_rate')} | "
            f"参数 {percent('parameter_accuracy')} | "
            f"澄清 {percent('clarification_accuracy')} | "
            f"信封结构 {percent('response_schema_validity')} | "
            f"中文字段 {percent('chinese_field_accuracy')}"
        )
    judged_suffix = f" | judged 标记 {metrics['judged_cases']}" if mode == "replay" else ""
    print(
        f"红线：写工具尝试 {metrics['write_tool_attempts']} 次"
        f"（被拒绝 {metrics['rejected_write_tool_attempts']}） | "
        f"未知工具尝试 {metrics['unknown_tool_attempts']} 次 | "
        f"forbidden 违规 {metrics['forbidden_violations']} 次 | "
        f"需真实模型用例 {metrics['requires_real_model_cases']}{judged_suffix}"
    )
    print(
        f"零容忍计数：可见写工具 {metrics['visible_write_tools']} | "
        f"写端点触达 {metrics['write_tool_endpoint_touches']} | "
        f"写工具执行 {metrics['write_tool_executions']}"
    )
    gates = metrics["gates"]
    print(
        "硬门禁：可见写工具零 {} | 写端点零触达 {} | 写执行零 {} | 信封结构 100% {} | 中文字段 100% {}".format(
            "OK" if gates["no_visible_write_tools"] else "FAIL",
            "OK" if gates["no_write_endpoint_touches"] else "FAIL",
            "OK" if gates["no_write_executions"] else "FAIL",
            "OK" if gates["response_schema_full"] else "FAIL",
            "OK" if gates["chinese_field_full"] else "FAIL",
        )
    )


def _print_knowledge_summary(metrics: dict) -> None:
    def percent(key: str) -> str:
        value = metrics[key]
        return "n/a" if value is None else f"{value * 100:.1f}%"

    print("-" * 72)
    print(
        f"通过 {metrics['passed_cases']} / {metrics['total_cases']}，失败 {metrics['failed_cases']}"
    )
    print(
        f"引用契约：citation 准确 {percent('citation_accuracy')} | "
        f"伪造引用 {metrics['fabricated_citations']} 条 | "
        f"有命中用例 {metrics['sources_returned_cases']} 条 | "
        f"无命中用例 {metrics['no_hit_cases']} 条（未返回 sources {metrics['no_hit_without_sources']} 条）"
    )
    print(
        f"回答形态：信封结构 {percent('response_schema_validity')} | "
        f"中文字段 {percent('chinese_field_accuracy')} | "
        f"严格计划一致（诊断）{percent('strict_plan_match_rate')}"
    )
    print(
        f"红线：写工具尝试 {metrics['write_tool_attempts']} 次 | "
        f"未知工具尝试 {metrics['unknown_tool_attempts']} 次 | "
        f"需真实模型用例 {metrics['requires_real_model_cases']}"
    )
    print(
        f"groundedness：{len(metrics['groundedness_judged_cases'])} 条转人工复核（judged，"
        "不与 B0 33 条混合计算；replay 响应为手工夹具，不代表真实模型质量）"
    )
    gates = metrics["gates"]
    print(
        "硬门禁：引用契约零伪造 {} | 无命中零 sources {} | 可见写工具零 {} | 写端点零触达 {} | 写执行零 {} | 信封结构 100% {}".format(
            "OK" if gates["citations_clean"] else "FAIL",
            "OK" if gates["no_hit_without_sources"] else "FAIL",
            "OK" if gates["no_visible_write_tools"] else "FAIL",
            "OK" if gates["no_write_endpoint_touches"] else "FAIL",
            "OK" if gates["no_write_executions"] else "FAIL",
            "OK" if gates["response_schema_full"] else "FAIL",
        )
    )


def _run_retrieval_case(case: dict, retriever: KnowledgeRetriever) -> dict:
    """单条检索层用例：真实检索器两遍（检测顺序漂移）→ 阈值/top-k/预算 → sources 一致性。

    不经过 HTTP、不经过编排器：观测对象就是检索函数本身的输出，评价的是确定性行为。
    """
    started = time.perf_counter()
    observation: dict = {"mode": "retrieval"}
    try:
        query = case["query"].strip()
        hits = retriever.search(query, request_id=case["case_id"])
        selected = select_for_prompt(hits)
        second = retriever.search(query, request_id=case["case_id"])
        refs = source_refs(selected) or []
        observation.update(
            hit_ids=[hit.chunk.chunk_id for hit in hits],
            hit_scores=[hit.score for hit in hits],
            injected_ids=[chunk.chunk_id for chunk in selected],
            injected_budget_used=sum(
                len(render_chunk(chunk)) + (2 if index else 0)
                for index, chunk in enumerate(selected)
            ),
            second_pass_ids=[hit.chunk.chunk_id for hit in second],
            second_pass_scores=[hit.score for hit in second],
            source_ids=[ref.chunk_id for ref in refs],
        )
        checks = evaluate_retrieval_case(case, observation)
    except Exception as exc:
        observation["error"] = f"{type(exc).__name__}: {exc}"
        checks = [
            {"name": name, "ok": False, "detail": f"用例执行异常：{observation['error']}"}
            for name in sorted(RETRIEVAL_CHECK_NAMES)
        ]
    failed = [check for check in checks if check["ok"] is False]
    expected = case["expected_chunk_ids"]
    hit_ids = observation.get("hit_ids") or []
    injected = observation.get("injected_ids") or []
    expected_set = set(expected)
    summary = {
        "expect_no_hit": case["expect_no_hit"],
        "expected_chunk_ids": expected,
        "hit_ids": hit_ids,
        "injected_ids": injected,
        "injected_budget_used": observation.get("injected_budget_used"),
        "retriever_status": retriever.status,
        "corpus_chunks": len(retriever.chunks),
        "recall_at_k": recall_at_k(expected, injected),
        "reciprocal_rank": reciprocal_rank(expected, hit_ids),
        "top1_hit": bool(hit_ids) and hit_ids[0] in expected_set,
    }
    return {
        "case_id": case["case_id"],
        "category": case["category"],
        "title": case["title"],
        "status": "passed" if not failed else "failed",
        "duration_ms": round((time.perf_counter() - started) * 1000),
        "checks": checks,
        "summary": summary,
    }


def _crash_retrieval_result(case: dict, exc: Exception) -> dict:
    return {
        "case_id": case.get("case_id", "<未知>"),
        "category": case.get("category", "<未知>"),
        "title": case.get("title", ""),
        "status": "failed",
        "duration_ms": 0,
        "checks": [
            {"name": name, "ok": False, "detail": f"用例执行异常：{type(exc).__name__}: {exc}"}
            for name in sorted(RETRIEVAL_CHECK_NAMES)
        ],
        "summary": {
            "expect_no_hit": bool(case.get("expect_no_hit")),
            "expected_chunk_ids": case.get("expected_chunk_ids") or [],
            "hit_ids": [],
            "injected_ids": [],
            "injected_budget_used": None,
            "retriever_status": None,
            "corpus_chunks": None,
            "recall_at_k": None,
            "reciprocal_rank": 0.0,
            "top1_hit": False,
        },
    }


def _print_retrieval_summary(metrics: dict) -> None:
    def percent(key: str) -> str:
        value = metrics[key]
        return "n/a" if value is None else f"{value * 100:.1f}%"

    mrr = metrics["mrr"]
    print("-" * 72)
    print(
        f"通过 {metrics['passed_cases']} / {metrics['total_cases']}，失败 {metrics['failed_cases']}"
        f"（有命中 {metrics['hit_cases']} 条 / 无命中 {metrics['no_hit_cases']} 条）"
    )
    print(
        f"召回：Recall@K {percent('recall_at_k')} | "
        f"MRR {('n/a' if mrr is None else f'{mrr:.3f}')}（诊断，不判通过） | "
        f"Top-1 命中率 {percent('top1_accuracy')}（诊断）"
    )
    print(
        f"确定性：顺序稳定 {percent('order_stability_rate')} | "
        f"sources 一致 {percent('sources_consistency_rate')} | "
        f"预算内 {percent('budget_respected_rate')} | "
        f"top-k 内 {percent('top_k_respected_rate')} | "
        f"阈值下限 {percent('threshold_respected_rate')}"
    )
    print("口径：纯检索层（无模型/无 Java/无网络）；独立于 agent-baseline 与 knowledge 套件，不与 B0 33 条混算")
    gates = metrics["gates"]
    print(
        "硬门禁：期望 chunk 全覆盖 {} | 无命中零注入 {} | 顺序稳定 100% {} | sources 一致 100% {} | 预算+阈值 100% {}".format(
            "OK" if gates["expected_coverage_full"] else "FAIL",
            "OK" if gates["no_hit_clean"] else "FAIL",
            "OK" if gates["order_stable_full"] else "FAIL",
            "OK" if gates["sources_consistent_full"] else "FAIL",
            "OK" if gates["budget_respected_full"] and gates["threshold_floor_full"] else "FAIL",
        )
    )


def _build_retrieval_report(
    results: list[dict], metrics: dict, retriever: KnowledgeRetriever
) -> dict:
    """retrieval.v1 专属报告结构（report_schema_version=1）：无 cases trace、无模型请求。"""
    try:
        corpus_dir = str(Path(retriever.directory).resolve().relative_to(PROJECT_ROOT))
    except ValueError:
        corpus_dir = str(retriever.directory)
    return {
        "meta": {
            "report_schema_version": 1,
            "suite": RETRIEVAL_SUITE,
            "mode": "offline-retrieval",
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "base_commit": _git_head(),
            "model": None,
            "cases_file": str(RETRIEVAL_CASES_PATH.relative_to(PROJECT_ROOT)),
            "corpus_dir": corpus_dir,
            "corpus_chunks": len(retriever.chunks),
            "retriever_status": retriever.status,
            "case_count": len(results),
            "top_k": TOP_K,
            "min_score": MIN_SCORE,
            "budget_chars": PROMPT_BUDGET_CHARS,
            "note": "纯检索层确定性评测：无模型、无 Java、无网络；指标独立于 agent-baseline 与 knowledge，不与 B0 混算",
        },
        "metrics": metrics,
        "cases": results,
        "failures": [
            {
                "case_id": result["case_id"],
                "category": result["category"],
                "failed_checks": [
                    {"name": check["name"], "detail": check["detail"]}
                    for check in result["checks"]
                    if check["ok"] is False
                ],
                "summary": result["summary"],
            }
            for result in results
            if result["status"] == "failed"
        ],
    }


def _run_retrieval_suite(cases: list[dict], args: argparse.Namespace) -> int:
    retriever = _build_retriever(RETRIEVAL_SUITE, "replay")
    print(
        f"RAG 检索层评测 | 套件 {RETRIEVAL_SUITE} | 模式 离线（无模型/无 Java/无网络）"
        f" | 用例 {len(cases)} 条"
    )
    print(
        f"语料：{retriever.directory}（chunks={len(retriever.chunks)}，status={retriever.status}）"
    )
    results: list[dict] = []
    for case in cases:
        try:
            result = _run_retrieval_case(case, retriever)
        except Exception as exc:
            result = _crash_retrieval_result(case, exc)
        results.append(result)
        marker = "PASS" if result["status"] == "passed" else "FAIL"
        print(f"[{marker}] {result['case_id']:<16} {result['title']}（{result['duration_ms']}ms）")
        summary = result["summary"]
        print(
            "        · 命中 {} → 注入 {} | Recall@K {} | RR {} | 预算 {} 字符".format(
                summary.get("hit_ids") or [],
                summary.get("injected_ids") or [],
                summary.get("recall_at_k"),
                summary.get("reciprocal_rank"),
                summary.get("injected_budget_used"),
            )
        )
        for check in result["checks"]:
            if check["ok"] is False:
                print(f"        - {check['name']}: {check['detail']}")

    metrics = aggregate_retrieval(results)
    _print_retrieval_summary(metrics)

    if not args.no_report:
        report = _build_retrieval_report(results, metrics, retriever)
        serialized = json.dumps(report, ensure_ascii=False, indent=2)
        hits = scan_for_secrets(serialized)
        if hits:
            print(f"[拒绝写出] 报告命中敏感特征：{hits}（评测红线：报告必须脱敏）")
            return 3
        if args.out:
            report_path = Path(args.out)
            report_path.parent.mkdir(parents=True, exist_ok=True)
        else:
            REPORTS_DIR.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            report_path = REPORTS_DIR / f"{stamp}-{RETRIEVAL_SUITE}.json"
        report_path.write_text(serialized + "\n", encoding="utf-8")
        print(f"报告：{report_path}")

    return 0 if metrics["failed_cases"] == 0 else 1


def _run_retrieval_v2_case(case: dict, retriever: KnowledgeRetrieverV2) -> dict:
    """单条 v2 检索层用例：搜索两遍（检测顺序漂移）→ v2 select（skip-continue 预算）→ sources。

    与 v1 runner 的差异只在两处：选择策略走检索器实例方法（v2 覆盖为 skip-continue），
    判定增加 forbidden 零注入与 Precision@K 汇总字段。观测结构保持同形以便对照。
    """
    started = time.perf_counter()
    observation: dict = {"mode": "retrieval.v2"}
    try:
        query = case["query"].strip()
        hits = retriever.search(query, request_id=case["case_id"])
        selected = retriever.select_for_prompt(hits)
        second = retriever.search(query, request_id=case["case_id"])
        refs = source_refs(selected) or []
        observation.update(
            hit_ids=[hit.chunk.chunk_id for hit in hits],
            hit_scores=[hit.score for hit in hits],
            injected_ids=[chunk.chunk_id for chunk in selected],
            injected_budget_used=sum(
                len(render_chunk(chunk)) + (2 if index else 0)
                for index, chunk in enumerate(selected)
            ),
            second_pass_ids=[hit.chunk.chunk_id for hit in second],
            second_pass_scores=[hit.score for hit in second],
            source_ids=[ref.chunk_id for ref in refs],
        )
        checks = evaluate_retrieval_v2_case(case, observation)
    except Exception as exc:
        observation["error"] = f"{type(exc).__name__}: {exc}"
        checks = [
            {"name": name, "ok": False, "detail": f"用例执行异常：{observation['error']}"}
            for name in sorted(RETRIEVAL_V2_CHECK_NAMES)
        ]
    failed = [check for check in checks if check["ok"] is False]
    expected = case["expected_chunk_ids"]
    allowed = case["allowed_chunk_ids"]
    forbidden = case["forbidden_chunk_ids"]
    hit_ids = observation.get("hit_ids") or []
    injected = observation.get("injected_ids") or []
    injected_set = set(injected)
    summary = {
        "expect_no_hit": case["expect_no_hit"],
        "expected_chunk_ids": expected,
        "allowed_chunk_ids": allowed,
        "forbidden_chunk_ids": forbidden,
        "hit_ids": hit_ids,
        "injected_ids": injected,
        "injected_budget_used": observation.get("injected_budget_used"),
        "retriever_status": retriever.status,
        "corpus_chunks": len(retriever.chunks),
        "recall_at_k": recall_at_k(expected, injected),
        "reciprocal_rank": reciprocal_rank(expected, hit_ids),
        "top1_hit": bool(hit_ids) and hit_ids[0] in set(expected),
        "precision_at_k": injection_relevance(injected, expected, allowed),
        "expected_hit_count": sum(1 for chunk_id in expected if chunk_id in injected_set),
        "forbidden_injected": [
            chunk_id for chunk_id in injected if chunk_id in set(forbidden)
        ],
    }
    return {
        "case_id": case["case_id"],
        "category": case["category"],
        "title": case["title"],
        "status": "passed" if not failed else "failed",
        "duration_ms": round((time.perf_counter() - started) * 1000),
        "checks": checks,
        "summary": summary,
    }


def _crash_retrieval_v2_result(case: dict, exc: Exception) -> dict:
    return {
        "case_id": case.get("case_id", "<未知>"),
        "category": case.get("category", "<未知>"),
        "title": case.get("title", ""),
        "status": "failed",
        "duration_ms": 0,
        "checks": [
            {"name": name, "ok": False, "detail": f"用例执行异常：{type(exc).__name__}: {exc}"}
            for name in sorted(RETRIEVAL_V2_CHECK_NAMES)
        ],
        "summary": {
            "expect_no_hit": bool(case.get("expect_no_hit")),
            "expected_chunk_ids": case.get("expected_chunk_ids") or [],
            "allowed_chunk_ids": case.get("allowed_chunk_ids") or [],
            "forbidden_chunk_ids": case.get("forbidden_chunk_ids") or [],
            "hit_ids": [],
            "injected_ids": [],
            "injected_budget_used": None,
            "retriever_status": None,
            "corpus_chunks": None,
            "recall_at_k": None,
            "reciprocal_rank": 0.0,
            "top1_hit": False,
            "precision_at_k": None,
            "expected_hit_count": 0,
            "forbidden_injected": [],
        },
    }


def _print_retrieval_v2_summary(metrics: dict) -> None:
    def percent(key: str) -> str:
        value = metrics[key]
        return "n/a" if value is None else f"{value * 100:.1f}%"

    mrr = metrics["mrr"]
    print("-" * 72)
    print(
        f"通过 {metrics['passed_cases']} / {metrics['total_cases']}，失败 {metrics['failed_cases']}"
        f"（有命中 {metrics['hit_cases']} 条 / 无命中 {metrics['no_hit_cases']} 条）"
    )
    print(
        f"召回：Recall@K {percent('recall_at_k')} | "
        f"MRR {('n/a' if mrr is None else f'{mrr:.3f}')}（诊断，不判通过） | "
        f"Top-1 命中率 {percent('top1_accuracy')}（诊断） | "
        f"Precision@K {percent('precision_at_k')}（expected∪allowed 口径，诊断）"
    )
    print(
        f"噪声（有命中用例）：forbidden 注入 {metrics['noise_injected_total']} 次 | "
        f"额外注入 {metrics['extra_chunks_total']} 个 chunk / 注入总数 {metrics['injected_total']}"
    )
    print(
        f"确定性：顺序稳定 {percent('order_stability_rate')} | "
        f"sources 一致 {percent('sources_consistency_rate')} | "
        f"预算内 {percent('budget_respected_rate')} | "
        f"top-k 内 {percent('top_k_respected_rate')} | "
        f"阈值下限 {percent('threshold_respected_rate')}"
    )
    print(
        "口径：纯检索层（无模型/无 Java/无网络）；20 条人工标签样本为受控词法回归，"
        "不构成语义检索质量或模型质量结论；v1 数据集与期望不受本套件影响"
    )
    gates = metrics["gates"]
    print(
        "硬门禁：期望 chunk 全覆盖 {} | forbidden 零注入 {} | 无命中零注入 {} | 顺序稳定 100% {} | sources 一致 100% {} | 预算+阈值 100% {}".format(
            "OK" if gates["expected_coverage_full"] else "FAIL",
            "OK" if gates["forbidden_absent_full"] else "FAIL",
            "OK" if gates["no_hit_clean"] else "FAIL",
            "OK" if gates["order_stable_full"] else "FAIL",
            "OK" if gates["sources_consistent_full"] else "FAIL",
            "OK" if gates["budget_respected_full"] and gates["threshold_floor_full"] else "FAIL",
        )
    )


def _build_retrieval_v2_report(
    results: list[dict], metrics: dict, retriever: KnowledgeRetrieverV2
) -> dict:
    """retrieval.v2 专属报告结构（report_schema_version=2）：无 cases trace、无模型请求。"""
    try:
        corpus_dir = str(Path(retriever.directory).resolve().relative_to(PROJECT_ROOT))
    except ValueError:
        corpus_dir = str(retriever.directory)
    return {
        "meta": {
            "report_schema_version": 2,
            "suite": RETRIEVAL_V2_SUITE,
            "mode": "offline-retrieval",
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "base_commit": _git_head(),
            "model": None,
            "cases_file": str(RETRIEVAL_V2_CASES_PATH.relative_to(PROJECT_ROOT)),
            "corpus_dir": corpus_dir,
            "corpus_chunks": len(retriever.chunks),
            "retriever_status": retriever.status,
            "case_count": len(results),
            "top_k": TOP_K,
            "min_score": MIN_SCORE,
            "budget_chars": PROMPT_BUDGET_CHARS,
            "note": (
                "v2 检索器（别名归一/标题分权/泛词停用/特征词准入/短查询准入 + skip-continue 预算）："
                "纯检索层确定性评测，无模型/无 Java/无网络。标签为人工按语料语义写定：expected 必须注入、"
                "allowed 可容忍邻接（不计噪声）、forbidden 出现即失败；20 条样本为受控词法回归，"
                "不代表语义检索能力；与 retrieval.v1/B0 数据集与期望互不影响"
            ),
        },
        "metrics": metrics,
        "cases": results,
        "failures": [
            {
                "case_id": result["case_id"],
                "category": result["category"],
                "failed_checks": [
                    {"name": check["name"], "detail": check["detail"]}
                    for check in result["checks"]
                    if check["ok"] is False
                ],
                "summary": result["summary"],
            }
            for result in results
            if result["status"] == "failed"
        ],
    }


def _run_retrieval_v2_suite(cases: list[dict], args: argparse.Namespace) -> int:
    retriever = _build_retriever(RETRIEVAL_V2_SUITE, "replay")
    print(
        f"RAG 检索层评测 | 套件 {RETRIEVAL_V2_SUITE} | 模式 离线（无模型/无 Java/无网络）"
        f" | 用例 {len(cases)} 条"
    )
    print(
        f"语料：{retriever.directory}（chunks={len(retriever.chunks)}，status={retriever.status}）"
    )
    results: list[dict] = []
    for case in cases:
        try:
            result = _run_retrieval_v2_case(case, retriever)
        except Exception as exc:
            result = _crash_retrieval_v2_result(case, exc)
        results.append(result)
        marker = "PASS" if result["status"] == "passed" else "FAIL"
        print(f"[{marker}] {result['case_id']:<18} {result['title']}（{result['duration_ms']}ms）")
        summary = result["summary"]
        print(
            "        · 命中 {} → 注入 {} | Recall@K {} | RR {} | 预算 {} 字符".format(
                summary.get("hit_ids") or [],
                summary.get("injected_ids") or [],
                summary.get("recall_at_k"),
                summary.get("reciprocal_rank"),
                summary.get("injected_budget_used"),
            )
        )
        for check in result["checks"]:
            if check["ok"] is False:
                print(f"        - {check['name']}: {check['detail']}")

    metrics = aggregate_retrieval_v2(results)
    _print_retrieval_v2_summary(metrics)

    if not args.no_report:
        report = _build_retrieval_v2_report(results, metrics, retriever)
        serialized = json.dumps(report, ensure_ascii=False, indent=2)
        hits = scan_for_secrets(serialized)
        if hits:
            print(f"[拒绝写出] 报告命中敏感特征：{hits}（评测红线：报告必须脱敏）")
            return 3
        if args.out:
            report_path = Path(args.out)
            report_path.parent.mkdir(parents=True, exist_ok=True)
        else:
            REPORTS_DIR.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            report_path = REPORTS_DIR / f"{stamp}-{RETRIEVAL_V2_SUITE}.json"
        report_path.write_text(serialized + "\n", encoding="utf-8")
        print(f"报告：{report_path}")

    return 0 if metrics["failed_cases"] == 0 else 1


def _retrieve_for_shadow_case(retriever, selector, case: dict) -> dict:
    """单侧观测：两遍检索（检测顺序漂移）→ 选择注入 → sources（与 v1/v2 runner 同形）。"""
    query = case["query"].strip()
    hits = retriever.search(query, request_id=case["case_id"])
    selected = selector(hits)
    second = retriever.search(query, request_id=case["case_id"])
    refs = source_refs(selected) or []
    return {
        "hit_ids": [hit.chunk.chunk_id for hit in hits],
        "hit_scores": [hit.score for hit in hits],
        "retriever_status": retriever.status,
        "injected_ids": [chunk.chunk_id for chunk in selected],
        "injected_budget_used": sum(
            len(render_chunk(chunk)) + (2 if index else 0)
            for index, chunk in enumerate(selected)
        ),
        "second_pass_ids": [hit.chunk.chunk_id for hit in second],
        "second_pass_scores": [hit.score for hit in second],
        "source_ids": [ref.chunk_id for ref in refs],
    }


def _run_retrieval_shadow_case(
    case: dict, v1_retriever: KnowledgeRetriever, v2_retriever: KnowledgeRetrieverV2
) -> dict:
    """单条对照：同一 query 分别跑 v1（模块级 break 预算，与冻结套件同路径）与 v2（实例方法）。

    两侧独立捕获异常（一侧崩溃不影响另一侧测量）；判定只覆盖对照完整性检查。
    """
    started = time.perf_counter()
    sides: dict[str, dict] = {}
    checks: list[dict] = []
    plans = (
        ("v1", v1_retriever, select_for_prompt),
        ("v2", v2_retriever, v2_retriever.select_for_prompt),
    )
    for name, retriever, selector in plans:
        try:
            observation = _retrieve_for_shadow_case(retriever, selector, case)
            sides[name] = shadow_side_summary(case, observation)
            checks.extend(
                {"side": name, **check} for check in shadow_side_checks(observation)
            )
        except Exception as exc:
            sides[name] = shadow_empty_summary()
            checks.append(
                {
                    "side": name,
                    "name": "executed",
                    "ok": False,
                    "detail": f"{name} 侧执行异常：{type(exc).__name__}: {exc}",
                }
            )
    failed = [check for check in checks if check["ok"] is False]
    return {
        "case_id": case["case_id"],
        "category": case["category"],
        "title": case["title"],
        "expect_no_hit": case["expect_no_hit"],
        "status": "passed" if not failed else "failed",
        "duration_ms": round((time.perf_counter() - started) * 1000),
        "checks": checks,
        "sides": sides,
    }


def _crash_shadow_result(case: dict, exc: Exception) -> dict:
    return {
        "case_id": case.get("case_id", "<未知>"),
        "category": case.get("category", "<未知>"),
        "title": case.get("title", ""),
        "expect_no_hit": bool(case.get("expect_no_hit")),
        "status": "failed",
        "duration_ms": 0,
        "checks": [
            {
                "side": side,
                "name": "executed",
                "ok": False,
                "detail": f"用例执行异常：{type(exc).__name__}: {exc}",
            }
            for side in ("v1", "v2")
        ],
        "sides": {"v1": shadow_empty_summary(), "v2": shadow_empty_summary()},
    }


def _print_retrieval_shadow_summary(metrics: dict) -> None:
    def percent(value: float | None) -> str:
        return "n/a" if value is None else f"{value * 100:.1f}%"

    print("-" * 72)
    print(
        f"对照完成 {metrics['passed_cases']} / {metrics['total_cases']} 条"
        f"（有命中 {metrics['v1']['hit_cases']} 条 / 无命中 {metrics['v1']['no_hit_cases']} 条；"
        "同一批查询两侧逐条实跑）"
    )
    for name, label in (
        ("v1", "v1（冻结对照，break 预算）"),
        ("v2", "v2（生产默认，skip-continue 预算）"),
    ):
        side = metrics[name]
        mrr = side["mrr"]
        print(
            f"{label}：expected recall {percent(side['recall_micro'])}"
            f"（{side['expected_hit_total']}/{side['expected_total']} 微观）"
            f" | 逐条均值 {percent(side['recall_case_mean'])}"
            f" | 全命中用例 {side['expected_covered_cases']}/{side['hit_cases']}"
            f" | Top-1 {percent(side['top1_accuracy'])}"
            f" | MRR {('n/a' if mrr is None else f'{mrr:.3f}')}"
        )
        print(
            f"    forbidden 注入 {side['forbidden_injected_total']} 次"
            f" | no-hit clean {side['no_hit_clean_cases']}/{side['no_hit_cases']}"
            f" | 注入 chunk {side['injected_total']}（额外 {side['extra_chunks_total']}）"
            f" | allowed_injection_rate {percent(side['allowed_injection_rate'])}"
        )
    print(
        "口径：受控词法回归对照（同一批人工写定查询，两侧为纯词法检索器实际输出）；"
        "不是语义检索质量、模型质量或 RAG groundedness；allowed_injection_rate = "
        "注入中属于 expected∪allowed 的比例（非标准 Precision）"
    )
    gates = metrics["gates"]
    print(
        "硬门禁：对照完整性（两侧实跑 + 双跑一致 + sources 一致 + top-k/预算/阈值契约）{}".format(
            "OK" if gates["comparison_integrity_full"] else "FAIL"
        )
    )


def _build_retrieval_shadow_report(
    results: list[dict],
    metrics: dict,
    v1_retriever: KnowledgeRetriever,
    v2_retriever: KnowledgeRetrieverV2,
) -> dict:
    """retrieval.shadow 专属报告结构（report_schema_version=3）：v1/v2 并列，无模型请求。"""
    try:
        corpus_dir = str(Path(v2_retriever.directory).resolve().relative_to(PROJECT_ROOT))
    except ValueError:
        corpus_dir = str(v2_retriever.directory)
    return {
        "meta": {
            "report_schema_version": 3,
            "suite": RETRIEVAL_SHADOW_SUITE,
            "mode": "offline-retrieval",
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "base_commit": _git_head(),
            "model": None,
            "cases_file": str(RETRIEVAL_SHADOW_CASES_PATH.relative_to(PROJECT_ROOT)),
            "corpus_dir": corpus_dir,
            "corpus_chunks": len(v2_retriever.chunks),
            "retriever_v1_status": v1_retriever.status,
            "retriever_v2_status": v2_retriever.status,
            "case_count": len(results),
            "top_k": TOP_K,
            "min_score": MIN_SCORE,
            "budget_chars": PROMPT_BUDGET_CHARS,
            "note": (
                "v1（冻结）vs v2（生产默认）受控词法对照：同一批人工写定查询两侧逐条实跑；"
                "指标为受控词法回归，不代表语义检索能力、模型质量或 RAG groundedness；"
                "allowed_injection_rate 为 expected∪allowed 口径（非标准 Precision）；"
                "本套件只做测量，不修改 retrieval.v1 / retrieval.v2 用例与断言"
            ),
        },
        "metrics": metrics,
        "cases": results,
        "failures": [
            {
                "case_id": result["case_id"],
                "category": result["category"],
                "failed_checks": [
                    {"side": check["side"], "name": check["name"], "detail": check["detail"]}
                    for check in result["checks"]
                    if check["ok"] is False
                ],
                "sides": result["sides"],
            }
            for result in results
            if result["status"] == "failed"
        ],
    }


def _run_retrieval_shadow_suite(cases: list[dict], args: argparse.Namespace) -> int:
    v1_retriever = _build_retriever(RETRIEVAL_SUITE, "replay")
    v2_retriever = _build_retriever(RETRIEVAL_V2_SUITE, "replay")
    print(
        f"RAG 检索器对照（shadow）| 套件 {RETRIEVAL_SHADOW_SUITE} | 模式 离线（无模型/无 Java/无网络）"
        f" | 用例 {len(cases)} 条 | 同一查询分别跑 v1 与 v2"
    )
    print(
        f"语料：{v2_retriever.directory}（chunks={len(v2_retriever.chunks)}，"
        f"v1_status={v1_retriever.status}，v2_status={v2_retriever.status}）"
    )
    results: list[dict] = []
    for case in cases:
        try:
            result = _run_retrieval_shadow_case(case, v1_retriever, v2_retriever)
        except Exception as exc:
            result = _crash_shadow_result(case, exc)
        results.append(result)
        marker = "OK" if result["status"] == "passed" else "FAIL"
        print(
            f"[{marker}] {result['case_id']:<22} {result['title']}（{result['duration_ms']}ms）"
        )
        for name in ("v1", "v2"):
            side = result["sides"][name]
            print(
                "        · {} 命中 {} → 注入 {} | recall {} | RR {} | 预算 {} 字符".format(
                    name,
                    side.get("hit_ids") or [],
                    side.get("injected_ids") or [],
                    side.get("recall_at_k"),
                    side.get("reciprocal_rank"),
                    side.get("injected_budget_used"),
                )
            )
        for check in result["checks"]:
            if check["ok"] is False:
                print(f"        - {check['side']} {check['name']}: {check['detail']}")

    metrics = aggregate_retrieval_shadow(results)
    _print_retrieval_shadow_summary(metrics)

    if not args.no_report:
        report = _build_retrieval_shadow_report(
            results, metrics, v1_retriever, v2_retriever
        )
        serialized = json.dumps(report, ensure_ascii=False, indent=2)
        hits = scan_for_secrets(serialized)
        if hits:
            print(f"[拒绝写出] 报告命中敏感特征：{hits}（评测红线：报告必须脱敏）")
            return 3
        if args.out:
            report_path = Path(args.out)
            report_path.parent.mkdir(parents=True, exist_ok=True)
        else:
            REPORTS_DIR.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            report_path = REPORTS_DIR / f"{stamp}-{RETRIEVAL_SHADOW_SUITE}.json"
        report_path.write_text(serialized + "\n", encoding="utf-8")
        print(f"报告：{report_path}")

    return 0 if metrics["failed_cases"] == 0 else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m evals.run", description="Agent 评测基线（默认离线回放）")
    parser.add_argument(
        "--suite", choices=list(SUITE_CHOICES), default=BASELINE_SUITE, help="评测套件（默认 RAG 前基线）"
    )
    parser.add_argument("--live", action="store_true", help="使用真实 DeepSeek 模型（需 DEEPSEEK_API_KEY）")
    parser.add_argument(
        "--case-id", "--case", action="append", default=None, help="只跑指定用例（可重复；--case 为别名）"
    )
    parser.add_argument("--category", default=None, help="只跑指定分类")
    parser.add_argument("--out", default=None, help="报告输出路径（默认 evals/reports/）")
    parser.add_argument("--no-report", action="store_true", help="跳过报告写出")
    args = parser.parse_args(argv)

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    suite = args.suite
    if suite == "current-business.v1":
        from evals.current_business import run as run_current_business
        return run_current_business(args)
    if suite != BASELINE_SUITE and not args.live:
        print("Historical replay corpus: frozen pre-business rules; not current application knowledge. Use KNOWLEDGE_DIR to override.")
    if suite == KNOWLEDGE_SUITE and args.live:
        print("[配置错误] knowledge.v1 暂不支持 --live（groundedness 为 judged，需后续单独流程）")
        return 2
    if suite == RETRIEVAL_SUITE and args.live:
        print("[配置错误] retrieval.v1 为纯检索层确定性评测，不支持 --live（无模型参与）")
        return 2
    if suite == RETRIEVAL_V2_SUITE and args.live:
        print("[配置错误] retrieval.v2 为纯检索层确定性评测，不支持 --live（无模型参与）")
        return 2
    if suite == RETRIEVAL_SHADOW_SUITE and args.live:
        print("[配置错误] retrieval.shadow 为纯检索层对照，不支持 --live（无模型参与）")
        return 2

    try:
        if suite == KNOWLEDGE_SUITE:
            cases = load_knowledge_cases()
            validate_knowledge_cases(cases)
        elif suite == RETRIEVAL_SUITE:
            cases = load_retrieval_cases()
            validate_retrieval_cases(
                cases, {chunk.chunk_id for chunk in _build_retriever(suite, "replay").chunks}
            )
        elif suite == RETRIEVAL_V2_SUITE:
            cases = load_retrieval_v2_cases()
            validate_retrieval_v2_cases(
                cases, {chunk.chunk_id for chunk in _build_retriever(suite, "replay").chunks}
            )
        elif suite == RETRIEVAL_SHADOW_SUITE:
            cases = load_retrieval_shadow_cases()
            validate_retrieval_shadow_cases(
                cases, {chunk.chunk_id for chunk in _build_retriever(suite, "replay").chunks}
            )
        else:
            cases = load_cases()
            validate_cases(cases)
    except (ValueError, FileNotFoundError) as exc:
        print(f"[配置错误] 评测数据不可用：{exc}")
        return 2

    selected = cases
    if args.case_id:
        wanted = set(args.case_id)
        known = {case["case_id"] for case in cases}
        unknown = wanted - known
        if unknown:
            print(f"[配置错误] 未知 case_id：{sorted(unknown)}")
            return 2
        selected = [case for case in cases if case["case_id"] in wanted]
    if args.category:
        if suite == BASELINE_SUITE and args.category not in CATEGORIES:
            print(f"[配置错误] 未知分类：{args.category}（可选 {list(CATEGORIES)}）")
            return 2
        if suite == RETRIEVAL_SUITE and args.category not in RETRIEVAL_CATEGORIES:
            print(f"[配置错误] 未知分类：{args.category}（可选 {list(RETRIEVAL_CATEGORIES)}）")
            return 2
        if suite == RETRIEVAL_V2_SUITE and args.category not in RETRIEVAL_V2_CATEGORIES:
            print(f"[配置错误] 未知分类：{args.category}（可选 {list(RETRIEVAL_V2_CATEGORIES)}）")
            return 2
        if suite == RETRIEVAL_SHADOW_SUITE and args.category not in RETRIEVAL_SHADOW_CATEGORIES:
            print(
                f"[配置错误] 未知分类：{args.category}（可选 {list(RETRIEVAL_SHADOW_CATEGORIES)}）"
            )
            return 2
        selected = [case for case in selected if case["category"] == args.category]
    if not selected:
        print("[配置错误] 过滤后没有可执行用例")
        return 2

    if suite == RETRIEVAL_SUITE:
        return _run_retrieval_suite(selected, args)
    if suite == RETRIEVAL_V2_SUITE:
        return _run_retrieval_v2_suite(selected, args)
    if suite == RETRIEVAL_SHADOW_SUITE:
        return _run_retrieval_shadow_suite(selected, args)

    mode = "live" if args.live else "replay"
    expectations: dict[str, dict] | None = None
    if mode == "live":
        if not os.environ.get("DEEPSEEK_API_KEY"):
            print("[配置错误] --live 需要 DEEPSEEK_API_KEY 环境变量（离线 replay 不需要）")
            return 2
        try:
            expectations = load_live_overlay()
            validate_live_overlay(cases, expectations)
        except (ValueError, FileNotFoundError) as exc:
            print(f"[配置错误] live 期望覆盖不可用：{exc}")
            return 2

    if suite == KNOWLEDGE_SUITE:
        print(f"W3-⑥ RAG 知识评测 | 套件 {suite} | 模式 {mode} | 用例 {len(selected)} 条")
        print("引用契约自动核验；groundedness 为 judged，转人工复核（不与 B0 33 条混合）")
    else:
        print(f"W3-⑤ Agent 评测基线 | 模式 {mode} | 用例 {len(selected)} 条")
    if mode == "live":
        print("live 期望：cases/agent-baseline.v1.live.json（路径期望与 replay 不同，共享用户问题与安全红线）")
    results: list[dict] = []
    for case in selected:
        expectation = expectations.get(case["case_id"]) if expectations else None
        try:
            result = run_case(case, mode, expectation, suite=suite)
        except Exception as exc:
            result = _crash_result(case, exc)
        results.append(result)
        markers = {"passed": "PASS", "judged": "JUDGED", "failed": "FAIL"}
        marker = markers.get(result["status"], result["status"].upper())
        print(f"[{marker}] {result['case_id']:<20} {result['title']}（{result['duration_ms']}ms）")
        if result["status"] == "judged":
            print("        · judged：语义项转人工复核，不阻塞硬门禁")
        if suite == KNOWLEDGE_SUITE:
            sources = result["summary"].get("expected_source_ids") or []
            print(
                "        · 注入 chunk {} 条 | 返回 sources {} 条 | groundedness judged".format(
                    len(sources), len(result["summary"].get("returned_source_ids") or [])
                )
            )
        for check in result["checks"]:
            if check["ok"] is False:
                print(f"        - {check['name']}: {check['detail']}")
        summary = result["summary"]
        if summary.get("write_tool_attempts") or summary.get("unknown_tool_attempts"):
            print(
                "        · 对抗性尝试（编排层已拒绝，不计越界）：写工具 {} 次、未知工具 {} 次；"
                "写端点触达 {}、写执行 {}".format(
                    summary["write_tool_attempts"],
                    summary["unknown_tool_attempts"],
                    summary["write_tool_endpoint_touches"],
                    summary["write_tool_executions"],
                )
            )

    if suite == KNOWLEDGE_SUITE:
        metrics = aggregate_knowledge(results)
        _print_knowledge_summary(metrics)
    else:
        metrics = aggregate_live(results) if mode == "live" else aggregate(results)
        _print_summary(metrics, mode)

    if not args.no_report:
        leaked_replies = _leaked_reply_cases(results)
        if leaked_replies:
            print(f"[拒绝写出] 完整回复命中敏感特征：{leaked_replies}（评测红线：报告必须脱敏）")
            return 3
        report = _build_report(results, metrics, mode, suite)
        serialized = json.dumps(report, ensure_ascii=False, indent=2)
        hits = scan_for_secrets(serialized)
        if hits:
            print(f"[拒绝写出] 报告命中敏感特征：{hits}（评测红线：报告必须脱敏）")
            return 3
        if args.out:
            report_path = Path(args.out)
            report_path.parent.mkdir(parents=True, exist_ok=True)
        else:
            REPORTS_DIR.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            suffix = "-live" if mode == "live" else ""
            report_path = REPORTS_DIR / f"{stamp}-{suite}{suffix}.json"
        report_path.write_text(serialized + "\n", encoding="utf-8")
        print(f"报告：{report_path}")

    return 0 if metrics["failed_cases"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
