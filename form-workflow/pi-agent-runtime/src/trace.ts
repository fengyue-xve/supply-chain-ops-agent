// Run/event trace for the Pi agent side.
//
// Recording policy (locked with contracts/README.md "审计与备注留存边界"):
// allowed — tool name, turn, event type, result class, stop reason, run_id,
// event_id, record_ref (sha256[:12]);
// forbidden — demo tokens, Authorization/Bearer headers, raw record ids,
// business values (proposed/current mark values), free-text notes, canaries.
//
// The scanner below is fail-closed: Trace.write() refuses to persist a trace
// that trips any forbidden pattern.

import fs from "node:fs";
import path from "node:path";
import type { AgentEvent } from "@earendil-works/pi-agent-core";

export type ResultClass =
  | "ok"
  | "applied"
  | "skipped"
  | "failed"
  | "uncertain"
  | "blocked"
  | "error";

export interface TraceEntry {
  seq: number;
  at: string;
  kind: "pi_event" | "tool_call" | "harness_step";
  phase?: string;
  turn?: number;
  event_type?: string;
  tool?: string;
  is_error?: boolean;
  step?: string;
  result_class?: ResultClass;
  reason?: string;
  java_status?: number;
  java_error_code?: string;
  outcome?: string;
  status?: string;
  run_id?: string;
  record_ref?: string;
  deduplicated?: boolean;
  write_count?: number;
}

export const TRACE_FORBIDDEN_PATTERNS: { pattern: RegExp; label: string }[] = [
  { pattern: /token-(?:op|ap|agent)-\d/, label: "demo_token" },
  { pattern: /Bearer\s+\S/i, label: "bearer_credential" },
  { pattern: /Authorization:/i, label: "authorization_header" },
  { pattern: /R-\d{4}/, label: "raw_record_id" },
  { pattern: /canary|机密备注/, label: "canary_value" },
  { pattern: /LZL04|BA99|HACK01/, label: "fixture_business_value" },
];

const FORBIDDEN_KEY_PARTS = ["token", "secret", "password", "authorization", "credential"];

export function scanTraceText(text: string): string[] {
  const hits: string[] = [];
  for (const { pattern, label } of TRACE_FORBIDDEN_PATTERNS) {
    if (pattern.test(text)) hits.push(label);
  }
  return hits;
}

export function scanTraceObject(value: unknown): string[] {
  const hits: string[] = [];
  const walk = (node: unknown, atKey: string): void => {
    if (typeof node === "string") {
      for (const { pattern, label } of TRACE_FORBIDDEN_PATTERNS) {
        if (pattern.test(node)) hits.push(`${label} at ${atKey}`);
      }
      return;
    }
    if (node !== null && typeof node === "object") {
      for (const [key, child] of Object.entries(node)) {
        const lower = key.toLowerCase();
        if (FORBIDDEN_KEY_PARTS.some((part) => lower.includes(part))) {
          hits.push(`forbidden key "${key}" at ${atKey}`);
        }
        walk(child, `${atKey}.${key}`);
      }
    }
  };
  walk(value, "$");
  return hits;
}

export class Trace {
  private readonly entries: TraceEntry[] = [];
  private seq = 0;
  private turns = 0;
  private toolCallCount = 0;
  private runId: string | undefined;

  constructor(
    readonly scenario: string,
    readonly title: string,
  ) {}

  private push(entry: Omit<TraceEntry, "seq" | "at">): TraceEntry {
    const full: TraceEntry = { seq: ++this.seq, at: new Date().toISOString(), ...entry };
    this.entries.push(full);
    return full;
  }

  setRun(runId: string): void {
    this.runId = runId;
  }

  get currentTurn(): number {
    return this.turns;
  }

  get toolCalls(): number {
    return this.toolCallCount;
  }

  piEvent(event: AgentEvent): void {
    switch (event.type) {
      case "agent_start":
        this.push({ kind: "pi_event", event_type: "agent_start" });
        break;
      case "agent_end":
        this.push({ kind: "pi_event", event_type: "agent_end" });
        break;
      case "turn_start":
        this.turns += 1;
        this.push({ kind: "pi_event", event_type: "turn_start", turn: this.turns });
        break;
      case "turn_end":
        this.push({ kind: "pi_event", event_type: "turn_end", turn: this.turns });
        break;
      case "tool_execution_start":
        this.push({ kind: "pi_event", event_type: "tool_execution_start", turn: this.turns, tool: event.toolName });
        break;
      case "tool_execution_end":
        this.push({
          kind: "pi_event",
          event_type: "tool_execution_end",
          turn: this.turns,
          tool: event.toolName,
          is_error: event.isError,
        });
        break;
      default:
        // message_* events are deliberately not recorded: model text could
        // carry business values; only lifecycle/tool events are traced.
        break;
    }
  }

  agentRunStart(phase: string): void {
    this.push({ kind: "pi_event", event_type: "agent_run_start", phase, turn: this.turns, run_id: this.runId });
  }

  agentRunEnd(phase: string, stopReason: string): void {
    this.push({
      kind: "pi_event",
      event_type: "agent_run_end",
      phase,
      turn: this.turns,
      run_id: this.runId,
      reason: stopReason,
    });
  }

  toolOutcome(entry: {
    phase: string;
    tool: string;
    result_class: ResultClass;
    reason?: string;
    java_status?: number;
    java_error_code?: string;
    outcome?: string;
    status?: string;
    record_ref?: string;
    write_count?: number;
  }): void {
    this.toolCallCount += 1;
    this.push({
      kind: "tool_call",
      turn: this.turns,
      run_id: this.runId,
      ...entry,
    });
  }

  harnessStep(entry: {
    step: string;
    java_status: number;
    result_class: ResultClass;
    reason?: string;
    java_error_code?: string;
    run_id?: string;
    record_ref?: string;
    deduplicated?: boolean;
    status?: string;
    write_count?: number;
  }): void {
    this.push({ kind: "harness_step", ...entry });
  }

  toolEntries(tool?: string): TraceEntry[] {
    return this.entries.filter((entry) => entry.kind === "tool_call" && (tool === undefined || entry.tool === tool));
  }

  data(): Record<string, unknown> {
    return {
      trace_version: "1.0",
      generated_by: "pi-agent-runtime (Pi Agent Core + faux model, offline)",
      scenario: this.scenario,
      title: this.title,
      run_id: this.runId ?? null,
      pi_source: "npm: @earendil-works/pi-agent-core + pi-ai @ 0.85.1",
      entries: this.entries,
    };
  }

  write(dir: string): string {
    const payload = this.data();
    const hits = scanTraceObject(payload);
    if (hits.length > 0) {
      throw new Error(`trace redaction scan failed for ${this.scenario}: ${hits.join(", ")}`);
    }
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${this.scenario}.json`);
    fs.writeFileSync(file, JSON.stringify(payload, null, 2) + "\n", "utf8");
    return file;
  }
}
