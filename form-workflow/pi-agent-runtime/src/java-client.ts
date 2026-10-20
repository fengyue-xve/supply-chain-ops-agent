// Java HTTP client. The Node side never touches the database; every business fact
// comes from java-flow-service over HTTP. Tokens are fixture demo tokens
// (fixtures/users.json) and are never logged, traced, or echoed in errors.

export interface JavaResult {
  http: number;
  ok: boolean;
  body: unknown;
  errorCode?: string;
}

export class FlowApiError extends Error {
  readonly http: number;
  readonly errorCode: string;

  constructor(http: number, errorCode: string) {
    super(`${errorCode} (HTTP ${http})`);
    this.http = http;
    this.errorCode = errorCode;
  }
}

const REQUEST_TIMEOUT_MS = 5000;

// Route table mirroring contracts/tools.schema.json (method + path). Kept as data
// so the contract test can assert Node routes == contract routes bidirectionally.
export const FLOW_ROUTES = {
  get_form_record: { method: "GET", path: "/api/flow/records/{record_id}" },
  validate_transition: { method: "POST", path: "/api/flow/validate-transition" },
  plan_transition: { method: "POST", path: "/api/flow/runs/{run_id}/plan" },
  get_run: { method: "GET", path: "/api/flow/runs/{run_id}" },
  request_approval: { method: "POST", path: "/api/flow/runs/{run_id}/request-approval" },
  apply_transition: { method: "POST", path: "/api/flow/runs/{run_id}/apply" },
  replay_failed: { method: "POST", path: "/api/flow/runs/{run_id}/replay" },
  submit_event: { method: "POST", path: "/api/flow/events" },
  approve: { method: "POST", path: "/api/flow/runs/{run_id}/approve" },
  reject: { method: "POST", path: "/api/flow/runs/{run_id}/reject" },
  get_stats: { method: "GET", path: "/api/flow/stats" },
} as const;

function routePath(name: keyof typeof FLOW_ROUTES, replacements: Record<string, string>): string {
  let path: string = FLOW_ROUTES[name].path;
  for (const [key, value] of Object.entries(replacements)) {
    path = path.replace(`{${key}}`, value);
  }
  return path;
}

export class FlowApi {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
  ) {}

  private async call(method: string, path: string, body?: unknown): Promise<JavaResult> {
    const response = await fetch(this.baseUrl + path, {
      method,
      headers: {
        "X-Demo-Token": this.token,
        ...(body === undefined ? {} : { "content-type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    let parsed: unknown = null;
    const text = await response.text();
    if (text.length > 0) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = null;
      }
    }
    const errorCode =
      !response.ok && parsed !== null && typeof parsed === "object" && "error_code" in parsed
        ? String((parsed as { error_code: unknown }).error_code)
        : undefined;
    return { http: response.status, ok: response.ok, body: parsed, errorCode };
  }

  submitEvent(body: unknown): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.submit_event.method, routePath("submit_event", {}), body);
  }

  getRecord(recordId: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.get_form_record.method, routePath("get_form_record", { record_id: recordId }));
  }

  validateTransition(body: unknown): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.validate_transition.method, routePath("validate_transition", {}), body);
  }

  plan(runId: string, plan: unknown): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.plan_transition.method, routePath("plan_transition", { run_id: runId }), plan);
  }

  requestApproval(runId: string, note?: string): Promise<JavaResult> {
    return this.call(
      FLOW_ROUTES.request_approval.method,
      routePath("request_approval", { run_id: runId }),
      note === undefined ? {} : { note },
    );
  }

  approve(runId: string, note?: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.approve.method, routePath("approve", { run_id: runId }), note === undefined ? {} : { note });
  }

  reject(runId: string, reason: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.reject.method, routePath("reject", { run_id: runId }), { reason });
  }

  apply(runId: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.apply_transition.method, routePath("apply_transition", { run_id: runId }));
  }

  replay(runId: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.replay_failed.method, routePath("replay_failed", { run_id: runId }));
  }

  getRun(runId: string): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.get_run.method, routePath("get_run", { run_id: runId }));
  }

  getStats(): Promise<JavaResult> {
    return this.call(FLOW_ROUTES.get_stats.method, routePath("get_stats", {}));
  }
}

export interface ApplyOutcomeBody {
  outcome: "APPLIED" | "SKIPPED" | "FAILED" | "UNCERTAIN";
  message?: string;
  run: { run_id: string; status: string; apply_result: string; write_count: number };
}

export interface RunBody {
  run_id: string;
  status: string;
  apply_result: string;
  write_count: number;
  replay_count: number;
  failure_reason: string | null;
  approval: { decision: string; actor_id: string; decided_at: string; note: string | null } | null;
}

export interface StatsBody {
  run_count: number;
  runs_by_status: Record<string, number>;
  total_write_count: number;
  total_skip_count: number;
  total_manual_review: number;
  apply_endpoint_hits: number;
  replay_endpoint_hits: number;
}
