// Offline integration test: the REAL Pi Agent loop (pi-agent-core) plus the
// official faux provider (pi-ai) drive our 7 tools against a StubApi standing in
// for java-flow-service. Zero network, zero API key.
//
// What this pins: tool wiring, the apply/replay pre-gates, the idempotency
// self-check, and framework-level param validation. What it does NOT prove:
// real-model quality — the model here is the scripted fake (documented in
// fixtures/report wording as "Fake Model, offline").

import test from "node:test";
import assert from "node:assert/strict";
import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai";
import { classifyStop, createFlowRuntime } from "../src/runtime.ts";
import { createFlowTools, type ToolState } from "../src/tools.ts";
import { Trace } from "../src/trace.ts";
import { deriveIdempotencyKey } from "../src/idem.ts";
import type { FlowApi, JavaResult } from "../src/java-client.ts";

class StubApi {
  getRecordCalls = 0;
  validateCalls = 0;
  planCalls = 0;
  getRunCalls = 0;
  requestApprovalCalls = 0;
  applyCalls = 0;
  replayCalls = 0;
  runStatus = "RECEIVED";
  lastPlan: Record<string, unknown> | undefined;

  private ok(body: unknown): JavaResult {
    return { http: 200, ok: true, body };
  }

  private runView(runId: string): Record<string, unknown> {
    return {
      run_id: runId,
      status: this.runStatus,
      apply_result: "NONE",
      write_count: 0,
      replay_count: 0,
      failure_reason: null,
      approval: null,
    };
  }

  getRecord(recordId: string): Promise<JavaResult> {
    this.getRecordCalls += 1;
    return Promise.resolve(
      this.ok({
        record_id: recordId,
        flow_type: "PRELOAD_OUTER_BOX_MARK",
        business_version: 3,
        sku_code: "SKU-DEMO-1",
        mark_rule: "RULE_BOX_A",
        outer_box_mark: null,
        mark_rule_source: null,
        mark_updated_at: null,
        updated_at: "2026-10-05T00:00:00Z",
      }),
    );
  }

  validateTransition(_body: unknown): Promise<JavaResult> {
    this.validateCalls += 1;
    return Promise.resolve(this.ok({ allowed: true, reasons: [], expected_record_version: 3 }));
  }

  plan(runId: string, plan: unknown): Promise<JavaResult> {
    this.planCalls += 1;
    this.lastPlan = plan as Record<string, unknown>;
    this.runStatus = "PLANNED";
    return Promise.resolve(this.ok(this.runView(runId)));
  }

  getRun(runId: string): Promise<JavaResult> {
    this.getRunCalls += 1;
    return Promise.resolve(this.ok(this.runView(runId)));
  }

  requestApproval(runId: string, _note?: string): Promise<JavaResult> {
    this.requestApprovalCalls += 1;
    this.runStatus = "WAITING_APPROVAL";
    return Promise.resolve(this.ok(this.runView(runId)));
  }

  apply(runId: string): Promise<JavaResult> {
    this.applyCalls += 1;
    this.runStatus = "SUCCEEDED";
    return Promise.resolve(
      this.ok({
        outcome: "APPLIED",
        run: { run_id: runId, status: "SUCCEEDED", apply_result: "APPLIED", write_count: 1 },
      }),
    );
  }

  replay(runId: string): Promise<JavaResult> {
    this.replayCalls += 1;
    this.runStatus = "SUCCEEDED";
    return Promise.resolve(
      this.ok({
        outcome: "APPLIED",
        run: { run_id: runId, status: "SUCCEEDED", apply_result: "APPLIED", write_count: 1 },
      }),
    );
  }
}

function makeRuntime(stub: StubApi) {
  const trace = new Trace("offline-test", "offline");
  const state: ToolState = {};
  const tools = createFlowTools({ api: stub as unknown as FlowApi, trace, phase: () => "test", state });
  const rt = createFlowRuntime({ systemPrompt: "offline test runtime", tools, trace });
  return { trace, rt };
}

const RUN_A = "run-aaaaaaaaaaaa";
const planArgs = {
  plan_version: "1.0",
  source_record_id: "R-1001",
  flow_type: "PRELOAD_OUTER_BOX_MARK",
  business_version: 3,
  target_field: "outer_box_mark",
  proposed_value: "LZL04",
  rule_source: "fixture-rule:box-a-v1",
  preconditions: [{ kind: "record_exists" }, { kind: "business_version_matches" }],
  idempotency_key: deriveIdempotencyKey("R-1001", "PRELOAD_OUTER_BOX_MARK", 3),
  risk: "MEDIUM",
  requires_human_approval: true,
};

test("plan phase: real Pi loop drives get_form_record -> plan_transition -> request_approval", async () => {
  const stub = new StubApi();
  const { trace, rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage([fauxToolCall("get_form_record", { record_id: "R-1001" })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxToolCall("plan_transition", { run_id: RUN_A, ...planArgs })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxToolCall("request_approval", { run_id: RUN_A })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxText("计划已提交，等待人工审批。")]),
  ]);
  const result = await rt.runPhase("plan", "读取 R-1001 并生成流转计划");
  assert.equal(result.stop_reason, "completed");
  assert.equal(stub.getRecordCalls, 1);
  assert.equal(stub.planCalls, 1);
  assert.equal(stub.requestApprovalCalls, 1);
  assert.equal(stub.applyCalls, 0);
  assert.equal(stub.lastPlan?.idempotency_key, planArgs.idempotency_key);
  assert.ok(!("run_id" in (stub.lastPlan ?? {})), "run_id stays a tool param, not part of the plan body");
  assert.deepEqual(
    trace.toolEntries().map((entry) => entry.tool),
    ["get_form_record", "plan_transition", "request_approval"],
  );
});

test("unapproved apply: client pre-gate blocks with zero endpoint touch", async () => {
  const stub = new StubApi();
  stub.runStatus = "WAITING_APPROVAL";
  const { trace, rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage([fauxToolCall("apply_transition", { run_id: "run-bbbbbbbbbbbb" })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxText("apply 已被客户端预检阻断，未触碰写入端点。")]),
  ]);
  const result = await rt.runPhase("execute", "尝试在未获批时执行写入");
  assert.equal(result.stop_reason, "completed");
  assert.equal(stub.applyCalls, 0);
  assert.equal(stub.getRunCalls, 1);
  const entry = trace.toolEntries("apply_transition").at(-1);
  assert.equal(entry?.result_class, "blocked");
  assert.equal(entry?.reason, "precheck_status_WAITING_APPROVAL");
});

test("approved apply: run status EXECUTING reaches the apply endpoint exactly once", async () => {
  const stub = new StubApi();
  stub.runStatus = "EXECUTING";
  const { trace, rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage([fauxToolCall("apply_transition", { run_id: RUN_A })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxText("写入完成。")]),
  ]);
  const result = await rt.runPhase("execute", "审批已通过，执行写入");
  assert.equal(result.stop_reason, "completed");
  assert.equal(stub.getRunCalls, 1);
  assert.equal(stub.applyCalls, 1);
  const entry = trace.toolEntries("apply_transition").at(-1);
  assert.equal(entry?.result_class, "applied");
  assert.equal(entry?.outcome, "APPLIED");
});

test("framework typebox validation rejects mistyped params before execute", async () => {
  const stub = new StubApi();
  const { rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage([fauxToolCall("get_form_record", { record_id: 123 })], { stopReason: "toolUse" }),
    fauxAssistantMessage([fauxText("参数被拒绝。")]),
  ]);
  const result = await rt.runPhase("test", "用错误类型参数调用工具");
  assert.equal(stub.getRecordCalls, 0, "invalid args must never reach execute()");
  assert.equal(result.stop_reason, "completed");
  const errorResults = rt.agent.state.messages.filter(
    (message) => message.role === "toolResult" && (message as { isError?: boolean }).isError === true,
  );
  assert.equal(errorResults.length, 1);
});

test("framework typebox validation rejects unknown params (additionalProperties=false)", async () => {
  const stub = new StubApi();
  const { rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage([fauxToolCall("get_form_record", { record_id: "R-1001", extra: "x" })], {
      stopReason: "toolUse",
    }),
    fauxAssistantMessage([fauxText("参数被拒绝。")]),
  ]);
  const result = await rt.runPhase("test", "用未知参数调用工具");
  assert.equal(stub.getRecordCalls, 0);
  assert.equal(result.stop_reason, "completed");
});

test("idempotency self-check refuses a pattern-valid but derivation-wrong key before HTTP", async () => {
  const stub = new StubApi();
  const { trace, rt } = makeRuntime(stub);
  rt.faux.setResponses([
    fauxAssistantMessage(
      [
        fauxToolCall("plan_transition", {
          run_id: "run-cccccccccccc",
          ...planArgs,
          idempotency_key: "FLOW_IDK_0000000000000000",
        }),
      ],
      { stopReason: "toolUse" },
    ),
    fauxAssistantMessage([fauxText("自我校验未通过。")]),
  ]);
  const result = await rt.runPhase("plan", "提交幂等键被篡改的计划");
  assert.equal(result.stop_reason, "completed");
  assert.equal(stub.planCalls, 0, "plan endpoint must not be touched");
  const entry = trace.toolEntries("plan_transition").at(-1);
  assert.equal(entry?.result_class, "error");
  assert.equal(entry?.reason, "idempotency_key_selfcheck");
});

test("model error details are classified but never copied into trace data", () => {
  const sensitiveError = "provider failure: Bearer token-agent-1 while processing LZL04";
  const result = classifyStop({ state: { errorMessage: sensitiveError } } as never);
  const trace = new Trace("model-error", "model error");
  trace.agentRunEnd("plan", result.stop_reason);

  assert.equal(result.stop_reason, "model_error");
  const entries = trace.data().entries as Array<Record<string, unknown>>;
  assert.equal(entries[0].reason, "model_error");
  assert.equal(Object.hasOwn(entries[0], "detail"), false);
  assert.equal(JSON.stringify(trace.data()).includes(sensitiveError), false);
});
