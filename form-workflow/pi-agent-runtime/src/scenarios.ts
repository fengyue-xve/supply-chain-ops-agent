// End-to-end business-loop scenarios for the offline demo.
//
// Every scenario drives the real Pi Agent (pi-agent-core) whose model turns are
// scripted through the official Faux provider — deterministic, zero network.
// All business facts (state machine, permissions, idempotency, writes) come from
// java-flow-service over HTTP; the Node side never touches a database.
//
// The harness performs "human" steps (submit event, approve, reject) with the
// matching demo role token, exactly as a human operator/approver would. The
// agent itself has no approve/reject tool and cannot act as approver.

import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai";
import { actorForRole, loadFixtureEvents, loadRules, tokenForRole, type FixtureEvent } from "./config.ts";
import { deriveIdempotencyKey, recordRef } from "./idem.ts";
import { FlowApi, type ApplyOutcomeBody, type JavaResult, type RunBody, type StatsBody } from "./java-client.ts";
import { createFlowRuntime, type FlowRuntime } from "./runtime.ts";
import { createFlowTools, type ToolState } from "./tools.ts";
import { Trace, type TraceEntry } from "./trace.ts";

const FLOW = "PRELOAD_OUTER_BOX_MARK";

export interface Check {
  name: string;
  ok: boolean;
  detail: string;
}

export interface ScenarioResult {
  name: string;
  title: string;
  runIds: string[];
  checks: Check[];
  trace: Trace;
}

class Checker {
  readonly checks: Check[] = [];

  eq(name: string, actual: unknown, expected: unknown, detail?: string): void {
    const ok = actual === expected;
    this.checks.push({
      name,
      ok,
      detail: `expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}${detail ? ` (${detail})` : ""}`,
    });
  }

  truthy(name: string, condition: boolean, detail: string): void {
    this.checks.push({ name, ok: condition, detail });
  }
}

const SYSTEM_PROMPT = [
  "你是供应链表单流转 Agent（Pi Agent Harness 驱动）。你只通过工具操作 java-flow-service；从不直接读写数据库。",
  "职责顺序：读取表单记录 → 校验预检 → 生成结构化流转计划（proposed_value/rule_source 必须来自规则表）→ 申请人工审批 → 获批（run 处于 EXECUTING）后执行写入 → 必要时重放对账。",
  "审批只能由 approver 完成，你没有审批工具，不得伪造审批。写入端点仅在 run 处于 EXECUTING 时可用。",
].join("\n");

const toolCallMsg = (name: string, args: Record<string, unknown>) =>
  fauxAssistantMessage([fauxToolCall(name, args)], { stopReason: "toolUse" });
const say = (text: string) => fauxAssistantMessage([fauxText(text)]);

interface Apis {
  ops: FlowApi;
  approver: FlowApi;
  agent: FlowApi;
}

function makeApis(baseUrl: string): Apis {
  return {
    ops: new FlowApi(baseUrl, tokenForRole("operator")),
    approver: new FlowApi(baseUrl, tokenForRole("approver")),
    agent: new FlowApi(baseUrl, tokenForRole("agent")),
  };
}

interface ScenarioCtx {
  name: string;
  title: string;
  trace: Trace;
  apis: Apis;
  chk: Checker;
  rt: FlowRuntime;
  phase: { current: string };
  state: ToolState;
  runIds: string[];
}

function makeCtx(baseUrl: string, name: string, title: string): ScenarioCtx {
  const trace = new Trace(name, title);
  const state: ToolState = {};
  const phase = { current: "plan" };
  const apis = makeApis(baseUrl);
  const tools = createFlowTools({ api: apis.agent, trace, phase: () => phase.current, state });
  const rt = createFlowRuntime({ systemPrompt: SYSTEM_PROMPT, tools, trace });
  return { name, title, trace, apis, chk: new Checker(), rt, phase, state, runIds: [] };
}

function bodyOf<T>(result: JavaResult): T {
  return result.body as T;
}

function fixtureEvent(eventId: string): FixtureEvent & { note: string | null } {
  const found = loadFixtureEvents().find((event) => event.event_id === eventId);
  if (!found) throw new Error(`fixture event ${eventId} not found`);
  return found;
}

function submitBody(event: FixtureEvent): Record<string, unknown> {
  return {
    event_id: event.event_id,
    source_record_id: event.source_record_id,
    flow_type: event.flow_type,
    business_version: event.business_version,
    submitted_by: event.submitted_by,
    note: event.note,
  };
}

function buildPlan(record: Record<string, unknown>, runId: string): Record<string, unknown> {
  const recordId = String(record.record_id);
  const flowType = String(record.flow_type);
  const version = Number(record.business_version);
  const rule = loadRules()[String(record.mark_rule)];
  if (!rule) throw new Error(`no rule entry for mark_rule ${String(record.mark_rule)}`);
  return {
    run_id: runId,
    plan_version: "1.0",
    source_record_id: recordId,
    flow_type: flowType,
    business_version: version,
    target_field: "outer_box_mark",
    proposed_value: rule.proposed_value,
    rule_source: rule.rule_source,
    preconditions: [
      { kind: "record_exists" },
      { kind: "business_version_matches" },
      { kind: "mark_rule_known" },
      { kind: "target_field_differs" },
      { kind: "idempotency_key_absent" },
    ],
    idempotency_key: deriveIdempotencyKey(recordId, flowType, version),
    risk: "MEDIUM",
    requires_human_approval: true,
  };
}

async function stats(apis: Apis): Promise<StatsBody> {
  const result = await apis.ops.getStats();
  if (!result.ok) throw new Error(`stats unavailable: HTTP ${result.http} (is java-flow-service running?)`);
  return bodyOf<StatsBody>(result);
}

async function runBody(apis: Apis, runId: string): Promise<RunBody> {
  const result = await apis.agent.getRun(runId);
  if (!result.ok) throw new Error(`getRun failed: HTTP ${result.http}`);
  return bodyOf<RunBody>(result);
}

function lastTool(trace: Trace, tool: string): TraceEntry | undefined {
  return trace.toolEntries(tool).at(-1);
}

function eqStat(chk: Checker, name: string, before: number, after: number, expectedDelta: number): void {
  chk.eq(name, after - before, expectedDelta, "stats delta");
}

function submitViaHarness(c: ScenarioCtx, event: FixtureEvent, label: string): Promise<JavaResult> {
  return c.apis.ops.submitEvent(submitBody(event)).then((result) => {
    c.trace.harnessStep({
      step: label,
      java_status: result.http,
      result_class: result.ok ? "ok" : "error",
      java_error_code: result.errorCode,
      record_ref: recordRef(event.source_record_id),
      deduplicated: result.ok ? Boolean((result.body as { deduplicated: boolean }).deduplicated) : undefined,
      status: result.ok ? String((result.body as { status: string }).status) : undefined,
      run_id: result.ok ? String((result.body as { run_id: string }).run_id) : undefined,
    });
    return result;
  });
}

function humanApprove(c: ScenarioCtx, runId: string, note: string): Promise<JavaResult> {
  return c.apis.approver.approve(runId, note).then((result) => {
    c.trace.harnessStep({
      step: "approve",
      java_status: result.http,
      result_class: result.ok ? "ok" : "error",
      java_error_code: result.errorCode,
      run_id: runId,
      status: result.ok ? String((result.body as RunBody).status) : undefined,
    });
    return result;
  });
}

export async function scenarioHappyFullChain(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "happy_full_chain", "批准执行只写一次 + 事件重放/重复执行幂等");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-001");
  const submit = await submitViaHarness(c, evt, "submit_event");
  c.chk.eq("submit_http", submit.http, 200);
  const runId = bodyOf<{ run_id: string; deduplicated: boolean }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);
  c.chk.truthy("submit_fresh", bodyOf<{ deduplicated: boolean }>(submit).deduplicated === false, "first submit must not be deduplicated");

  const rec0 = bodyOf<Record<string, unknown>>(await c.apis.agent.getRecord("R-1001"));
  c.chk.truthy("record_mark_initially_empty", rec0.outer_box_mark === null, "outer_box_mark starts empty");

  c.phase.current = "plan";
  const businessVersion = Number(rec0.business_version);
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-1001" }),
    toolCallMsg("validate_transition", { source_record_id: "R-1001", flow_type: FLOW, business_version: businessVersion }),
    () => toolCallMsg("plan_transition", buildPlan(c.state.lastRecord ?? rec0, runId)),
    toolCallMsg("request_approval", { run_id: runId, note: "demo: plan ready, requesting approval" }),
    say("计划已提交，等待人工审批。"),
  ]);
  const phasePlan = await c.rt.runPhase("plan", `流转事件 ${evt.event_id} 已提交（run ${runId}）。请读取记录、完成预检并提交结构化计划，然后申请人工审批。`);
  c.chk.eq("phase_plan_stop", phasePlan.stop_reason, "completed");

  const afterPlan = await runBody(c.apis, runId);
  c.chk.eq("status_after_plan", afterPlan.status, "WAITING_APPROVAL");
  c.chk.eq("writes_before_approval", afterPlan.write_count, 0);

  const approved = await humanApprove(c, runId, "演示审批：同意执行");
  c.chk.truthy("approve_by_approver", approved.ok, `HTTP ${approved.http}`);
  const afterApprove = bodyOf<RunBody>(approved);
  c.chk.eq("status_after_approve", afterApprove.status, "EXECUTING");
  c.chk.truthy(
    "approval_decision_recorded",
    afterApprove.approval?.decision === "APPROVED" && afterApprove.approval?.actor_id === actorForRole("approver"),
    `decision=${afterApprove.approval?.decision} actor=${afterApprove.approval?.actor_id}`,
  );

  c.phase.current = "execute";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    toolCallMsg("get_run", { run_id: runId }),
    say("写入完成。"),
  ]);
  const phaseExecute = await c.rt.runPhase("execute", "审批已通过（approver ap-1）。请执行写入并确认运行状态。");
  c.chk.eq("phase_execute_stop", phaseExecute.stop_reason, "completed");
  c.chk.eq("apply_outcome_applied", lastTool(c.trace,"apply_transition")?.outcome, "APPLIED");

  const afterExec = await runBody(c.apis, runId);
  c.chk.eq("status_after_execute", afterExec.status, "SUCCEEDED");
  c.chk.eq("write_count_run", afterExec.write_count, 1);

  const rec1 = bodyOf<{ outer_box_mark: { value: string; rule_source: string } | null }>(await c.apis.agent.getRecord("R-1001"));
  c.chk.truthy(
    "record_written_once_with_rule_value",
    rec1.outer_box_mark?.value === "LZL04" && rec1.outer_box_mark?.rule_source === "fixture-rule:box-a-v1",
    `mark=${JSON.stringify(rec1.outer_box_mark)}`,
  );

  // Duplicate apply on a SUCCEEDED run: must be SKIPPED with zero additional write.
  c.phase.current = "idempotent_replay";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    say("重复执行已按幂等语义跳过。"),
  ]);
  await c.rt.runPhase("idempotent_replay", "请再次执行写入以验证幂等。");
  c.chk.eq("duplicate_apply_skipped", lastTool(c.trace,"apply_transition")?.result_class, "skipped");

  // Event replay: same event_id resubmitted -> deduplicated, no new run, no write.
  const resubmit = await submitViaHarness(c, evt, "submit_event_replay");
  const resubmitBody = bodyOf<{ run_id: string; deduplicated: boolean }>(resubmit);
  c.chk.truthy("event_replay_deduplicated", resubmit.http === 200 && resubmitBody.deduplicated === true, JSON.stringify(resubmitBody));
  c.chk.eq("event_replay_same_run", resubmitBody.run_id, runId);

  // Direct replay probe on a SUCCEEDED run must be rejected by Java (409), zero write.
  const replayProbe = await c.apis.agent.replay(runId);
  c.trace.harnessStep({
    step: "replay_probe_on_succeeded",
    java_status: replayProbe.http,
    result_class: replayProbe.ok ? "ok" : "error",
    java_error_code: replayProbe.errorCode,
  });
  c.chk.truthy("replay_probe_rejected", replayProbe.http === 409 && replayProbe.errorCode === "INVALID_TRANSITION", `HTTP ${replayProbe.http} ${replayProbe.errorCode}`);

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta", before.total_write_count, after.total_write_count, 1);
  // total_skip_count counts runs whose apply_result converged to SKIPPED; the
  // duplicate apply is a repeat call on an already-SUCCEEDED run, so the run
  // stays APPLIED and the counter must NOT move (zero-write is asserted above).
  eqStat(c.chk, "skips_delta_dup_apply_creates_no_new_skip", before.total_skip_count, after.total_skip_count, 0);
  eqStat(c.chk, "run_count_delta", before.run_count, after.run_count, 1);
  eqStat(c.chk, "apply_endpoint_hits_delta", before.apply_endpoint_hits, after.apply_endpoint_hits, 2);
  eqStat(c.chk, "replay_endpoint_hits_delta", before.replay_endpoint_hits, after.replay_endpoint_hits, 1);

  return finish(c);
}

export async function scenarioUnapprovedBlocked(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "unapproved_blocked", "未批准执行：Agent 门禁零触达 + Java 越门即拒");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-002");
  const submit = await submitViaHarness(c, evt, "submit_event");
  const runId = bodyOf<{ run_id: string }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);

  const rec0 = bodyOf<Record<string, unknown>>(await c.apis.agent.getRecord("R-1002"));
  c.phase.current = "plan";
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-1002" }),
    toolCallMsg("validate_transition", { source_record_id: "R-1002", flow_type: FLOW, business_version: Number(rec0.business_version) }),
    () => toolCallMsg("plan_transition", buildPlan(c.state.lastRecord ?? rec0, runId)),
    toolCallMsg("request_approval", { run_id: runId, note: "demo: requesting approval" }),
    say("计划已提交，等待人工审批。"),
  ]);
  await c.rt.runPhase("plan", `流转事件 ${evt.event_id} 已提交（run ${runId}）。请读取记录、完成预检、提交计划并申请审批。`);
  const afterPlan = await runBody(c.apis, runId);
  c.chk.eq("status_after_plan", afterPlan.status, "WAITING_APPROVAL");

  // The scripted "model" now attempts to write WITHOUT approval.
  c.phase.current = "unapproved_attempt";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    say("写入被阻止：该运行尚未获得审批。"),
  ]);
  await c.rt.runPhase("unapproved_attempt", "请直接执行写入。");
  const blocked = lastTool(c.trace,"apply_transition");
  c.chk.truthy("agent_gate_blocked", blocked?.result_class === "blocked" && blocked?.reason === "precheck_status_WAITING_APPROVAL", `result_class=${blocked?.result_class} reason=${blocked?.reason}`);

  const afterAttempt = await stats(c.apis);
  eqStat(c.chk, "apply_endpoint_hits_delta_zero", before.apply_endpoint_hits, afterAttempt.apply_endpoint_hits, 0);
  eqStat(c.chk, "writes_delta_zero_before_probe", before.total_write_count, afterAttempt.total_write_count, 0);

  const recUnchanged = bodyOf<{ outer_box_mark: unknown }>(await c.apis.agent.getRecord("R-1002"));
  c.chk.truthy("record_untouched", recUnchanged.outer_box_mark === null, "mark still empty after blocked attempt");

  // Bypass the client gate entirely: Java must still reject with zero write.
  const probe = await c.apis.agent.apply(runId);
  c.trace.harnessStep({
    step: "direct_apply_probe_unapproved",
    java_status: probe.http,
    result_class: probe.ok ? "ok" : "error",
    java_error_code: probe.errorCode,
  });
  c.chk.truthy("java_rejects_direct_probe", probe.http === 409 && probe.errorCode === "INVALID_TRANSITION", `HTTP ${probe.http} ${probe.errorCode}`);

  const afterProbe = await stats(c.apis);
  eqStat(c.chk, "apply_endpoint_hits_delta_after_probe", before.apply_endpoint_hits, afterProbe.apply_endpoint_hits, 1);
  eqStat(c.chk, "writes_delta_zero", before.total_write_count, afterProbe.total_write_count, 0);
  const recAfterProbe = bodyOf<{ outer_box_mark: unknown }>(await c.apis.agent.getRecord("R-1002"));
  c.chk.truthy("record_untouched_after_probe", recAfterProbe.outer_box_mark === null, "mark still empty after server-side rejection");

  return finish(c);
}

export async function scenarioEscalationAndReject(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "escalation_reject", "operator 越权审批被拒 + approver 拒绝路径");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-002");
  const recovered = await submitViaHarness(c, evt, "recover_run_via_dedup");
  const runId = bodyOf<{ run_id: string }>(recovered).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);
  c.chk.truthy("run_recovered_deduplicated", bodyOf<{ deduplicated: boolean }>(recovered).deduplicated === true, "same event returns existing run");

  const opApprove = await c.apis.ops.approve(runId, "operator 不该能批准");
  c.trace.harnessStep({
    step: "operator_attempt_approve",
    java_status: opApprove.http,
    result_class: opApprove.ok ? "ok" : "error",
    java_error_code: opApprove.errorCode,
  });
  c.chk.truthy("operator_approve_forbidden", opApprove.http === 403 && opApprove.errorCode === "FORBIDDEN_ROLE", `HTTP ${opApprove.http} ${opApprove.errorCode}`);

  const agentApprove = await c.apis.agent.approve(runId, "agent 不该能批准");
  c.trace.harnessStep({
    step: "agent_attempt_approve",
    java_status: agentApprove.http,
    result_class: agentApprove.ok ? "ok" : "error",
    java_error_code: agentApprove.errorCode,
  });
  c.chk.truthy("agent_approve_forbidden", agentApprove.http === 403 && agentApprove.errorCode === "FORBIDDEN_ROLE", `HTTP ${agentApprove.http} ${agentApprove.errorCode}`);

  const stillWaiting = await runBody(c.apis, runId);
  c.chk.eq("status_unchanged_after_forbidden", stillWaiting.status, "WAITING_APPROVAL");

  const rejected = await c.apis.approver.reject(runId, "演示：审批拒绝");
  c.trace.harnessStep({
    step: "reject",
    java_status: rejected.http,
    result_class: rejected.ok ? "ok" : "error",
    java_error_code: rejected.errorCode,
  });
  const rejectedBody = bodyOf<RunBody>(rejected);
  c.chk.eq("status_after_reject", rejectedBody.status, "REJECTED");
  c.chk.truthy("reject_note_is_business_record", rejectedBody.approval?.note === "演示：审批拒绝", "reject reason is kept in approval.note only");

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta_zero", before.total_write_count, after.total_write_count, 0);
  eqStat(c.chk, "apply_endpoint_hits_delta_zero", before.apply_endpoint_hits, after.apply_endpoint_hits, 0);

  return finish(c);
}

export async function scenarioSameValueSkipped(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "same_value_skipped", "同值回写：SUCCEEDED 但零写入");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-003");
  const submit = await submitViaHarness(c, evt, "submit_event");
  const runId = bodyOf<{ run_id: string }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);

  const rec0 = bodyOf<{ outer_box_mark: { value: string } | null }>(await c.apis.agent.getRecord("R-1003"));
  c.chk.truthy("record_already_has_rule_value", rec0.outer_box_mark?.value === "LZL04", JSON.stringify(rec0.outer_box_mark));

  c.phase.current = "plan";
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-1003" }),
    toolCallMsg("validate_transition", { source_record_id: "R-1003", flow_type: FLOW, business_version: 2 }),
    () => toolCallMsg("plan_transition", buildPlan(c.state.lastRecord ?? rec0, runId)),
    toolCallMsg("request_approval", { run_id: runId, note: "demo: same-value plan" }),
    say("计划已提交，等待人工审批。"),
  ]);
  await c.rt.runPhase("plan", `流转事件 ${evt.event_id} 已提交（run ${runId}）。请完成计划与审批申请。`);

  await humanApprove(c, runId, "演示审批：同意执行（同值）");

  c.phase.current = "execute";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    say("记录已是目标值，本次按同值跳过，零写入。"),
  ]);
  await c.rt.runPhase("execute", "审批已通过。请执行写入。");
  const applied = lastTool(c.trace,"apply_transition");
  c.chk.eq("same_value_outcome", applied?.outcome, "SKIPPED");
  c.chk.eq("same_value_result_class", applied?.result_class, "skipped");

  const afterRun = await runBody(c.apis, runId);
  c.chk.eq("status_succeeded", afterRun.status, "SUCCEEDED");
  c.chk.eq("apply_result_skipped", afterRun.apply_result, "SKIPPED");

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta_zero", before.total_write_count, after.total_write_count, 0);
  eqStat(c.chk, "skips_delta", before.total_skip_count, after.total_skip_count, 1);

  return finish(c);
}

export async function scenarioUncertainReplay(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "uncertain_replay", "结果不确定 → MANUAL_REVIEW → replay 对账零重复写");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-103");
  const submit = await submitViaHarness(c, evt, "submit_event");
  const runId = bodyOf<{ run_id: string }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);

  const rec0 = bodyOf<Record<string, unknown>>(await c.apis.agent.getRecord("R-9004"));
  c.phase.current = "plan";
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-9004" }),
    toolCallMsg("validate_transition", { source_record_id: "R-9004", flow_type: FLOW, business_version: 6 }),
    () => toolCallMsg("plan_transition", buildPlan(c.state.lastRecord ?? rec0, runId)),
    toolCallMsg("request_approval", { run_id: runId, note: "demo: uncertain scenario" }),
    say("计划已提交，等待人工审批。"),
  ]);
  await c.rt.runPhase("plan", `流转事件 ${evt.event_id} 已提交（run ${runId}）。请完成计划与审批申请。`);
  await humanApprove(c, runId, "演示审批：同意执行（不确定场景）");

  c.phase.current = "execute";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    say("写入结果不确定，等待人工复核与重放对账。"),
  ]);
  await c.rt.runPhase("execute", "审批已通过。请执行写入。");
  const applied = lastTool(c.trace,"apply_transition");
  c.chk.eq("uncertain_outcome", applied?.outcome, "UNCERTAIN");

  const afterApply = await runBody(c.apis, runId);
  c.chk.eq("status_manual_review", afterApply.status, "MANUAL_REVIEW");
  c.chk.truthy("failure_reason_recorded", String(afterApply.failure_reason ?? "").includes("WRITE_UNCERTAIN"), `failure_reason=${afterApply.failure_reason}`);

  const statsAfterApply = await stats(c.apis);
  eqStat(c.chk, "uncertain_write_landed", before.total_write_count, statsAfterApply.total_write_count, 1);

  c.phase.current = "replay";
  c.rt.faux.appendResponses([
    toolCallMsg("replay_failed", { run_id: runId }),
    toolCallMsg("get_run", { run_id: runId }),
    say("重放对账完成：值已在位，未重复写入。"),
  ]);
  await c.rt.runPhase("replay", "该运行处于人工复核。请执行重放对账。");
  const replayed = lastTool(c.trace,"replay_failed");
  c.chk.eq("replay_reconciled_skipped", replayed?.result_class, "skipped");

  const afterReplay = await runBody(c.apis, runId);
  c.chk.eq("status_replay_succeeded", afterReplay.status, "SUCCEEDED");
  c.chk.eq("write_count_single", afterReplay.write_count, 1);

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta_no_double_write", before.total_write_count, after.total_write_count, 1);
  eqStat(c.chk, "skips_delta", before.total_skip_count, after.total_skip_count, 1);
  eqStat(c.chk, "replay_endpoint_hits_delta", before.replay_endpoint_hits, after.replay_endpoint_hits, 1);

  const rec = bodyOf<{ outer_box_mark: { value: string } | null }>(await c.apis.agent.getRecord("R-9004"));
  c.chk.truthy("record_final_value", rec.outer_box_mark?.value === "BA99", JSON.stringify(rec.outer_box_mark));

  return finish(c);
}

export async function scenarioFailOnceReplay(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "fail_once_replay", "写入失败一次 → MANUAL_REVIEW → replay 成功且恰好写一次");
  const before = await stats(c.apis);

  const evt = fixtureEvent("evt-101");
  const submit = await submitViaHarness(c, evt, "submit_event");
  const runId = bodyOf<{ run_id: string }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);

  const rec0 = bodyOf<Record<string, unknown>>(await c.apis.agent.getRecord("R-9002"));
  c.phase.current = "plan";
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-9002" }),
    toolCallMsg("validate_transition", { source_record_id: "R-9002", flow_type: FLOW, business_version: 4 }),
    () => toolCallMsg("plan_transition", buildPlan(c.state.lastRecord ?? rec0, runId)),
    toolCallMsg("request_approval", { run_id: runId, note: "demo: fail-once scenario" }),
    say("计划已提交，等待人工审批。"),
  ]);
  await c.rt.runPhase("plan", `流转事件 ${evt.event_id} 已提交（run ${runId}）。请完成计划与审批申请。`);
  await humanApprove(c, runId, "演示审批：同意执行（失败注入场景）");

  c.phase.current = "execute";
  c.rt.faux.appendResponses([
    toolCallMsg("apply_transition", { run_id: runId }),
    say("写入失败，已进入人工复核。"),
  ]);
  await c.rt.runPhase("execute", "审批已通过。请执行写入。");
  c.chk.eq("apply_failed_outcome", lastTool(c.trace,"apply_transition")?.outcome, "FAILED");

  const afterFail = await runBody(c.apis, runId);
  c.chk.eq("status_manual_review", afterFail.status, "MANUAL_REVIEW");
  c.chk.eq("write_count_zero_after_fail", afterFail.write_count, 0);

  c.phase.current = "replay";
  c.rt.faux.appendResponses([
    toolCallMsg("replay_failed", { run_id: runId }),
    toolCallMsg("get_run", { run_id: runId }),
    say("重放完成，写入收敛。"),
  ]);
  await c.rt.runPhase("replay", "该运行处于人工复核。请执行重放对账。");
  c.chk.eq("replay_applied", lastTool(c.trace,"replay_failed")?.result_class, "applied");

  const afterReplay = await runBody(c.apis, runId);
  c.chk.eq("status_succeeded", afterReplay.status, "SUCCEEDED");
  c.chk.eq("write_count_single", afterReplay.write_count, 1);

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta", before.total_write_count, after.total_write_count, 1);
  eqStat(c.chk, "replay_endpoint_hits_delta", before.replay_endpoint_hits, after.replay_endpoint_hits, 1);

  return finish(c);
}

export async function scenarioMaliciousAndStale(baseUrl: string): Promise<ScenarioResult> {
  const c = makeCtx(baseUrl, "malicious_and_stale", "模型越值计划被拒 + 旧版本事件零覆盖");

  const stale = fixtureEvent("evt-201");
  const staleSubmit = await submitViaHarness(c, stale, "submit_stale_event");
  c.chk.truthy(
    "stale_event_rejected",
    staleSubmit.http === 409 && staleSubmit.errorCode === "STALE_EVENT",
    `HTTP ${staleSubmit.http} ${staleSubmit.errorCode}`,
  );

  const before = await stats(c.apis);
  const custom = {
    event_id: "evt-301",
    source_record_id: "R-8001",
    flow_type: FLOW,
    business_version: 7,
    submitted_by: "op-1",
    note: "custom event for malicious-plan scenario",
  };
  const submit = await c.apis.ops.submitEvent(custom).then((result) => {
    c.trace.harnessStep({
      step: "submit_event_custom",
      java_status: result.http,
      result_class: result.ok ? "ok" : "error",
      java_error_code: result.errorCode,
      record_ref: recordRef("R-8001"),
    });
    return result;
  });
  c.chk.eq("custom_submit_http", submit.http, 200);
  const runId = bodyOf<{ run_id: string }>(submit).run_id;
  c.trace.setRun(runId);
  c.runIds.push(runId);

  const toolNames = c.rt.agent.state.tools.map((tool) => tool.name);
  c.chk.truthy(
    "agent_tools_exclude_approve_reject",
    !toolNames.includes("approve") && !toolNames.includes("reject") && toolNames.length === 7,
    `tools=${toolNames.join(",")}`,
  );

  const rec0 = bodyOf<Record<string, unknown>>(await c.apis.agent.getRecord("R-8001"));
  c.phase.current = "plan_malicious";
  c.rt.faux.appendResponses([
    toolCallMsg("get_form_record", { record_id: "R-8001" }),
    () => {
      const malicious = buildPlan(c.state.lastRecord ?? rec0, runId);
      malicious.proposed_value = "HACK01";
      return toolCallMsg("plan_transition", malicious);
    },
    say("计划被服务端拒绝，记录保持原状。"),
  ]);
  await c.rt.runPhase("plan_malicious", `流转事件 evt-301 已提交（run ${runId}）。请读取记录并提交计划。`);
  const planEntry = lastTool(c.trace,"plan_transition");
  c.chk.truthy(
    "malicious_plan_rejected_by_java",
    planEntry?.result_class === "error" && planEntry?.java_status === 422 && planEntry?.java_error_code === "PLAN_INVALID",
    `class=${planEntry?.result_class} http=${planEntry?.java_status} code=${planEntry?.java_error_code}`,
  );

  const after = await stats(c.apis);
  eqStat(c.chk, "writes_delta_zero", before.total_write_count, after.total_write_count, 0);
  const afterRun = await runBody(c.apis, runId);
  c.chk.eq("run_still_received", afterRun.status, "RECEIVED");
  const rec = bodyOf<{ outer_box_mark: unknown }>(await c.apis.agent.getRecord("R-8001"));
  c.chk.truthy("record_untouched", rec.outer_box_mark === null, "mark still empty");

  return finish(c);
}

function finish(c: ScenarioCtx): ScenarioResult {
  return {
    name: c.name,
    title: c.title,
    runIds: c.runIds,
    checks: c.chk.checks,
    trace: c.trace,
  };
}

export interface ScenarioDef {
  name: string;
  run: (baseUrl: string) => Promise<ScenarioResult>;
}

export const SCENARIOS: ScenarioDef[] = [
  { name: "happy_full_chain", run: scenarioHappyFullChain },
  { name: "unapproved_blocked", run: scenarioUnapprovedBlocked },
  { name: "escalation_reject", run: scenarioEscalationAndReject },
  { name: "same_value_skipped", run: scenarioSameValueSkipped },
  { name: "uncertain_replay", run: scenarioUncertainReplay },
  { name: "fail_once_replay", run: scenarioFailOnceReplay },
  { name: "malicious_and_stale", run: scenarioMaliciousAndStale },
];
