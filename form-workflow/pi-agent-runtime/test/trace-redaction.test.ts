// Redaction policy tests: the Node-side trace must never carry demo tokens,
// credentials, raw record ids, or business values — write() is fail-closed.
// Message content and tool args are structurally excluded from recording.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { AgentEvent } from "@earendil-works/pi-agent-core";
import { Trace, scanTraceObject, scanTraceText } from "../src/trace.ts";

test("text scanner catches every forbidden class and passes clean text", () => {
  assert.ok(scanTraceText("auth token-op-1").includes("demo_token"));
  assert.ok(scanTraceText("auth token-ap-2").includes("demo_token"));
  assert.ok(scanTraceText("auth token-agent-3").includes("demo_token"));
  assert.ok(scanTraceText("header Bearer abc.def").includes("bearer_credential"));
  assert.ok(scanTraceText("Authorization: something").includes("authorization_header"));
  assert.ok(scanTraceText("record R-1001 touched").includes("raw_record_id"));
  assert.ok(scanTraceText("canary string").includes("canary_value"));
  assert.ok(scanTraceText("含机密备注字段").includes("canary_value"));
  assert.ok(scanTraceText("value LZL04").includes("fixture_business_value"));
  assert.ok(scanTraceText("value BA99").includes("fixture_business_value"));
  assert.ok(scanTraceText("value HACK01").includes("fixture_business_value"));
  assert.deepEqual(scanTraceText("status=EXECUTING run-abc123def456 write_count=1"), []);
});

test("object scanner walks nested values and flags forbidden keys", () => {
  const hits = scanTraceObject({ outer: { inner: ["token-op-1"] }, access_token: "clean" });
  assert.ok(hits.some((hit) => hit.startsWith("demo_token at $.outer.inner")));
  assert.ok(hits.some((hit) => hit.includes('forbidden key "access_token"')));
  assert.deepEqual(scanTraceObject({ status: "SUCCEEDED", record_ref: "aeb28f94adb0" }), []);
});

test("pi events record lifecycle/tool metadata but never message content or tool args", () => {
  const trace = new Trace("t", "t");
  trace.piEvent({
    type: "message_end",
    message: { content: [{ type: "text", text: "写入 LZL04 完成" }] },
  } as unknown as AgentEvent);
  trace.piEvent({
    type: "tool_execution_start",
    toolCallId: "call-1",
    toolName: "plan_transition",
    args: { proposed_value: "LZL04", idempotency_key: "FLOW_IDK_0123456789abcdef" },
  } as unknown as AgentEvent);
  const entries = trace.data().entries as Array<Record<string, unknown>>;
  assert.equal(entries.length, 1);
  assert.equal(entries[0].tool, "plan_transition");
  const json = JSON.stringify(trace.data());
  assert.ok(!json.includes("LZL04"));
  assert.ok(!json.includes("FLOW_IDK_"));
  assert.ok(!json.includes("写入"));
});

test("write() persists clean traces and refuses redaction violations (fail-closed)", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "trace-redaction-"));
  try {
    const clean = new Trace("clean-scenario", "clean");
    clean.setRun("run-aaaaaaaaaaaa");
    clean.toolOutcome({
      phase: "plan",
      tool: "plan_transition",
      result_class: "ok",
      java_status: 200,
      status: "PLANNED",
      record_ref: "aeb28f94adb0",
    });
    const file = clean.write(dir);
    assert.equal(file, path.join(dir, "clean-scenario.json"));
    const persisted = JSON.parse(fs.readFileSync(file, "utf8")) as { scenario: string; entries: unknown[] };
    assert.equal(persisted.scenario, "clean-scenario");
    assert.equal(persisted.entries.length, 1);

    const rawId = new Trace("raw-id", "leak");
    rawId.setRun("R-1001");
    assert.throws(() => rawId.write(dir), /trace redaction scan failed/);

    const credential = new Trace("cred", "leak");
    credential.harnessStep({ step: "submit", java_status: 200, result_class: "ok", reason: "Bearer token-op-1" });
    assert.throws(() => credential.write(dir), /trace redaction scan failed/);

    assert.ok(!fs.existsSync(path.join(dir, "raw-id.json")));
    assert.ok(!fs.existsSync(path.join(dir, "cred.json")));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
