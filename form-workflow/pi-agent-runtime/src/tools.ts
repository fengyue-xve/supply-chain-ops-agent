// The 7 agent tools, mirroring contracts/tools.schema.json (agent_tools).
// Every tool routes to java-flow-service over HTTP with the agent demo token.
// Tool output text goes to the model; the Trace records only tool name, turn,
// result class, Java status/error code, run_id and record_ref — never values.

import type { AgentTool, AgentToolResult } from "@earendil-works/pi-agent-core";
import { Type, type Static } from "@earendil-works/pi-ai";
import { deriveIdempotencyKey, recordRef } from "./idem.ts";
import { FlowApi, FlowApiError, type ApplyOutcomeBody, type RunBody } from "./java-client.ts";
import type { ResultClass, Trace } from "./trace.ts";

const RunId = Type.String({ pattern: "^run-[0-9a-f]{12}$" });
const RecordId = Type.String({ pattern: "^R-[0-9]{4}$" });
const FlowTypeParam = Type.Literal("PRELOAD_OUTER_BOX_MARK");
const PreconditionKind = Type.Union([
  Type.Literal("record_exists"),
  Type.Literal("business_version_matches"),
  Type.Literal("mark_rule_known"),
  Type.Literal("target_field_differs"),
  Type.Literal("idempotency_key_absent"),
]);

const GetFormRecordParams = Type.Object(
  { record_id: RecordId },
  { additionalProperties: false },
);

const ValidateTransitionParams = Type.Object(
  {
    source_record_id: RecordId,
    flow_type: FlowTypeParam,
    business_version: Type.Integer({ minimum: 1 }),
  },
  { additionalProperties: false },
);

const PlanTransitionParams = Type.Object(
  {
    run_id: RunId,
    plan_version: Type.Literal("1.0"),
    source_record_id: RecordId,
    flow_type: FlowTypeParam,
    business_version: Type.Integer({ minimum: 1 }),
    target_field: Type.Literal("outer_box_mark"),
    proposed_value: Type.String({ pattern: "^[A-Z0-9]{2,12}$" }),
    rule_source: Type.String({ pattern: "^fixture-rule:[a-z0-9-]{2,64}$" }),
    preconditions: Type.Array(
      Type.Object(
        {
          kind: PreconditionKind,
          detail: Type.Optional(Type.Object({})),
        },
        { additionalProperties: false },
      ),
      { minItems: 1 },
    ),
    idempotency_key: Type.String({ pattern: "^FLOW_IDK_[0-9a-f]{16}$" }),
    risk: Type.Union([Type.Literal("LOW"), Type.Literal("MEDIUM"), Type.Literal("HIGH")]),
    requires_human_approval: Type.Boolean(),
  },
  { additionalProperties: false },
);

const GetRunParams = Type.Object({ run_id: RunId }, { additionalProperties: false });
const RequestApprovalParams = Type.Object(
  { run_id: RunId, note: Type.Optional(Type.String({ maxLength: 200 })) },
  { additionalProperties: false },
);
const ApplyTransitionParams = Type.Object({ run_id: RunId }, { additionalProperties: false });
const ReplayFailedParams = Type.Object({ run_id: RunId }, { additionalProperties: false });

export interface ToolState {
  lastRecord?: Record<string, unknown>;
  lastRun?: RunBody;
}

export interface ToolContext {
  api: FlowApi;
  trace: Trace;
  phase: () => string;
  state: ToolState;
}

function textResult<T>(text: string, details: T): AgentToolResult<T> {
  return { content: [{ type: "text", text }], details };
}

function assertKeys(tool: string, params: Record<string, unknown>, allowed: string[]): void {
  for (const key of Object.keys(params)) {
    if (!allowed.includes(key)) {
      throw new Error(`invalid parameter "${key}" for tool ${tool}`);
    }
  }
}

function requireOk<T>(result: { http: number; ok: boolean; body: unknown; errorCode?: string }, tool: string): T {
  if (!result.ok) {
    throw new FlowApiError(result.http, result.errorCode ?? "INTERNAL");
  }
  return result.body as T;
}

const OUTCOME_CLASS: Record<ApplyOutcomeBody["outcome"], ResultClass> = {
  APPLIED: "applied",
  SKIPPED: "skipped",
  FAILED: "failed",
  UNCERTAIN: "uncertain",
};

export function createFlowTools(ctx: ToolContext): AgentTool<any>[] {
  const { api, trace, state } = ctx;

  const getFormRecord: AgentTool<typeof GetFormRecordParams> = {
    name: "get_form_record",
    label: "读取表单记录",
    description: "按 record_id 读取表单流转记录（只读）。",
    parameters: GetFormRecordParams,
    async execute(_id, params) {
      assertKeys("get_form_record", params, ["record_id"]);
      try {
        const body = requireOk<Record<string, unknown>>(await api.getRecord(params.record_id), "get_form_record");
        state.lastRecord = body;
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "get_form_record",
          result_class: "ok",
          java_status: 200,
          record_ref: recordRef(params.record_id),
          status: String(body.business_version),
        });
        return textResult(
          `record read: mark_rule=${String(body.mark_rule)} business_version=${String(body.business_version)} current_mark=${body.outer_box_mark === null ? "none" : "present"}`,
          { record_ref: recordRef(params.record_id), mark_rule: body.mark_rule, business_version: body.business_version },
        );
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "get_form_record", error);
      }
    },
  };

  const validateTransition: AgentTool<typeof ValidateTransitionParams> = {
    name: "validate_transition",
    label: "校验流转预检",
    description: "只读预检：该记录的当前版本是否允许发起流转计划。",
    parameters: ValidateTransitionParams,
    async execute(_id, params) {
      assertKeys("validate_transition", params, ["source_record_id", "flow_type", "business_version"]);
      try {
        const body = requireOk<{ allowed: boolean; reasons: string[]; expected_record_version: number | null }>(
          await api.validateTransition(params),
          "validate_transition",
        );
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "validate_transition",
          result_class: "ok",
          java_status: 200,
          record_ref: recordRef(params.source_record_id),
          status: body.allowed ? "allowed" : "not_allowed",
        });
        return textResult(
          `precheck allowed=${body.allowed} reasons=${body.reasons.join(",") || "none"} expected_record_version=${body.expected_record_version}`,
          { allowed: body.allowed, reasons: body.reasons },
        );
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "validate_transition", error);
      }
    },
  };

  const planTransition: AgentTool<typeof PlanTransitionParams> = {
    name: "plan_transition",
    label: "提交流转计划",
    description: "把结构化流转计划提交给 Java 服务（RECEIVED→PLANNED）。Java 侧做最终校验。",
    parameters: PlanTransitionParams,
    async execute(_id, params) {
      assertKeys("plan_transition", params, [
        "run_id",
        "plan_version",
        "source_record_id",
        "flow_type",
        "business_version",
        "target_field",
        "proposed_value",
        "rule_source",
        "preconditions",
        "idempotency_key",
        "risk",
        "requires_human_approval",
      ]);
      const derived = deriveIdempotencyKey(params.source_record_id, params.flow_type, params.business_version);
      if (derived !== params.idempotency_key) {
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "plan_transition",
          result_class: "error",
          reason: "idempotency_key_selfcheck",
          record_ref: recordRef(params.source_record_id),
        });
        throw new Error("idempotency key does not match derivation; refusing to submit plan");
      }
      try {
        const { run_id, ...plan } = params;
        const body = requireOk<RunBody>(await api.plan(run_id, plan), "plan_transition");
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "plan_transition",
          result_class: "ok",
          java_status: 200,
          status: body.status,
          record_ref: recordRef(params.source_record_id),
        });
        return textResult(`plan recorded; run status=${body.status}`, { status: body.status });
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "plan_transition", error);
      }
    },
  };

  const getRun: AgentTool<typeof GetRunParams> = {
    name: "get_run",
    label: "读取运行状态",
    description: "按 run_id 读取运行视图（含状态、审批、写入计数、审计序列）。",
    parameters: GetRunParams,
    async execute(_id, params) {
      assertKeys("get_run", params, ["run_id"]);
      try {
        const body = requireOk<RunBody>(await api.getRun(params.run_id), "get_run");
        state.lastRun = body;
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "get_run",
          result_class: "ok",
          java_status: 200,
          status: body.status,
          write_count: body.write_count,
        });
        return textResult(
          `run status=${body.status} apply_result=${body.apply_result} write_count=${body.write_count} approval=${body.approval?.decision ?? "none"}`,
          { status: body.status, apply_result: body.apply_result, write_count: body.write_count },
        );
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "get_run", error);
      }
    },
  };

  const requestApproval: AgentTool<typeof RequestApprovalParams> = {
    name: "request_approval",
    label: "申请人工审批",
    description: "把已制定的计划提交人工审批（PLANNED→WAITING_APPROVAL）。审批只能由 approver 完成。",
    parameters: RequestApprovalParams,
    async execute(_id, params) {
      assertKeys("request_approval", params, ["run_id", "note"]);
      try {
        const body = requireOk<RunBody>(await api.requestApproval(params.run_id, params.note), "request_approval");
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "request_approval",
          result_class: "ok",
          java_status: 200,
          status: body.status,
        });
        return textResult(`approval requested; run status=${body.status}`, { status: body.status });
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "request_approval", error);
      }
    },
  };

  const preGateBlocked = (tool: string, status: string): AgentToolResult<unknown> => {
    trace.toolOutcome({
      phase: ctx.phase(),
      tool,
      result_class: "blocked",
      reason: `precheck_status_${status}`,
      java_status: 200,
      status,
    });
    return textResult(
      `blocked by client pre-gate: run status is ${status}; the write endpoint was NOT touched. A missing approval is a blocking error.`,
      { blocked: true, status },
    );
  };

  const applyTransition: AgentTool<typeof ApplyTransitionParams> = {
    name: "apply_transition",
    label: "执行流转写入",
    description: "在审批通过（run 处于 EXECUTING）后执行写入。未经审批不得执行。",
    parameters: ApplyTransitionParams,
    async execute(_id, params) {
      assertKeys("apply_transition", params, ["run_id"]);
      try {
        const pre = await api.getRun(params.run_id);
        const run = requireOk<RunBody>(pre, "apply_transition");
        if (run.status !== "EXECUTING" && run.status !== "SUCCEEDED") {
          return preGateBlocked("apply_transition", run.status);
        }
        const body = requireOk<ApplyOutcomeBody>(await api.apply(params.run_id), "apply_transition");
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "apply_transition",
          result_class: OUTCOME_CLASS[body.outcome],
          java_status: 200,
          outcome: body.outcome,
          status: body.run.status,
          write_count: body.run.write_count,
        });
        return textResult(
          `apply outcome=${body.outcome} run status=${body.run.status} write_count=${body.run.write_count}`,
          { outcome: body.outcome, status: body.run.status, write_count: body.run.write_count },
        );
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "apply_transition", error);
      }
    },
  };

  const replayFailed: AgentTool<typeof ReplayFailedParams> = {
    name: "replay_failed",
    label: "重放对账",
    description: "仅对处于 MANUAL_REVIEW 的运行做重放对账；结果确定后收敛为 SUCCEEDED/FAILED。",
    parameters: ReplayFailedParams,
    async execute(_id, params) {
      assertKeys("replay_failed", params, ["run_id"]);
      try {
        const pre = await api.getRun(params.run_id);
        const run = requireOk<RunBody>(pre, "replay_failed");
        if (run.status !== "MANUAL_REVIEW") {
          return preGateBlocked("replay_failed", run.status);
        }
        const body = requireOk<ApplyOutcomeBody>(await api.replay(params.run_id), "replay_failed");
        trace.toolOutcome({
          phase: ctx.phase(),
          tool: "replay_failed",
          result_class: OUTCOME_CLASS[body.outcome],
          java_status: 200,
          outcome: body.outcome,
          status: body.run.status,
          write_count: body.run.write_count,
        });
        return textResult(
          `replay outcome=${body.outcome} run status=${body.run.status} write_count=${body.run.write_count}`,
          { outcome: body.outcome, status: body.run.status, write_count: body.run.write_count },
        );
      } catch (error) {
        throw recordToolError(trace, ctx.phase(), "replay_failed", error);
      }
    },
  };

  return [
    getFormRecord,
    validateTransition,
    planTransition,
    getRun,
    requestApproval,
    applyTransition,
    replayFailed,
  ];
}

function recordToolError(trace: Trace, phase: string, tool: string, error: unknown): Error {
  if (error instanceof FlowApiError) {
    trace.toolOutcome({
      phase,
      tool,
      result_class: "error",
      java_status: error.http,
      java_error_code: error.errorCode,
    });
    return error;
  }
  trace.toolOutcome({ phase, tool, result_class: "error", reason: "client_error" });
  return error instanceof Error ? error : new Error(String(error));
}
