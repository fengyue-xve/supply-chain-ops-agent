// Offline demo CLI: runs the scripted end-to-end scenarios against a running
// java-flow-service, prints per-check PASS/FAIL, write/skip/manual-review
// counters and trace paths, and writes demo-summary.json.
//
// Usage (from repo root):
//   pi-agent-runtime/node_modules/.bin/tsx --tsconfig pi-agent-runtime/tsconfig.json \
//     pi-agent-runtime/src/cli.ts [--scenario NAME] [--java URL] [--traces DIR]
//
// Requires: java-flow-service freshly booted (fixtures seeded; fault-injection
// counters are single-use per boot).

import fs from "node:fs";
import path from "node:path";
import { javaBaseUrl as defaultJavaUrl, tokenForRole, tracesDir as defaultTracesDir } from "./config.ts";
import { FlowApi, type StatsBody } from "./java-client.ts";
import { SCENARIOS, type ScenarioResult } from "./scenarios.ts";

function argValue(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

async function main(): Promise<number> {
  const baseUrl = argValue("--java") ?? defaultJavaUrl;
  const tracesDir = argValue("--traces") ?? defaultTracesDir;
  const only = argValue("--scenario");

  const probe = new FlowApi(baseUrl, tokenForRole("operator"));
  const reachable = await probe.getStats().catch(() => null);
  if (!reachable?.ok) {
    console.error(`java-flow-service not reachable at ${baseUrl} (GET /api/flow/stats failed).`);
    console.error("Boot it first, e.g.: cd java-flow-service && mvn -o spring-boot:run");
    return 2;
  }

  const selected = only ? SCENARIOS.filter((scenario) => scenario.name === only) : SCENARIOS;
  if (selected.length === 0) {
    console.error(`unknown scenario "${only}". Known: ${SCENARIOS.map((s) => s.name).join(", ")}`);
    return 2;
  }
  if (only && only !== "happy_full_chain" && only !== "unapproved_blocked" && only !== "escalation_reject") {
    console.log("note: scenarios are ordered; escalation_reject depends on unapproved_blocked having run first.");
  }

  fs.mkdirSync(tracesDir, { recursive: true });

  const results: { result: ScenarioResult; traceFile: string }[] = [];
  for (const scenario of selected) {
    console.log("");
    console.log("=".repeat(78));
    console.log(`scenario ${scenario.name}`);
    console.log("=".repeat(78));
    let result: ScenarioResult;
    try {
      result = await scenario.run(baseUrl);
    } catch (error) {
      result = {
        name: scenario.name,
        title: scenario.name,
        runIds: [],
        checks: [
          {
            name: "scenario_exception",
            ok: false,
            detail: error instanceof Error ? error.message : String(error),
          },
        ],
        trace: undefined as never,
      };
    }
    let traceFile = "";
    if (result.trace) {
      try {
        traceFile = result.trace.write(tracesDir);
      } catch (error) {
        result.checks.push({
          name: "trace_write",
          ok: false,
          detail: error instanceof Error ? error.message : String(error),
        });
      }
    }
    for (const check of result.checks) {
      console.log(`  ${check.ok ? "PASS" : "FAIL"}  ${check.name}  ${check.detail}`);
    }
    if (result.runIds.length > 0) {
      console.log(`  run_ids: ${result.runIds.join(", ")}`);
    }
    if (traceFile) {
      console.log(`  trace:   ${path.relative(process.cwd(), traceFile)}`);
    }
    results.push({ result, traceFile });
  }

  const statsResult = await probe.getStats();
  const stats = statsResult.body as StatsBody;
  const allChecks = results.flatMap(({ result }) => result.checks);
  const failed = allChecks.filter((check) => !check.ok);
  const passed = allChecks.length - failed.length;

  console.log("");
  console.log("=".repeat(78));
  console.log("demo summary (Fake Model / faux provider; offline; no real model calls)");
  console.log("=".repeat(78));
  console.log(`checks            = ${passed}/${allChecks.length} passed`);
  console.log(`run_count         = ${stats.run_count}`);
  console.log(
    `runs_by_status    = ${Object.entries(stats.runs_by_status)
      .map(([status, count]) => `${status}=${count}`)
      .join(", ")}`,
  );
  console.log(`total_write_count = ${stats.total_write_count}`);
  console.log(`total_skip_count  = ${stats.total_skip_count}`);
  console.log(`total_manual_review = ${stats.total_manual_review}`);
  console.log(`apply_endpoint_hits  = ${stats.apply_endpoint_hits}`);
  console.log(`replay_endpoint_hits = ${stats.replay_endpoint_hits}`);
  console.log(`trace_dir         = ${tracesDir}`);

  const summary = {
    generated_at: new Date().toISOString(),
    model: "official Faux provider (packages/ai/src/providers/faux.ts) — scripted, offline, zero network",
    pi_source: "npm: @earendil-works/pi-agent-core + pi-ai @ 0.85.1",
    java_base_url: baseUrl,
    scenarios: results.map(({ result, traceFile }) => ({
      name: result.name,
      title: result.title,
      run_ids: result.runIds,
      checks_passed: result.checks.filter((check) => check.ok).length,
      checks_total: result.checks.length,
      failed_checks: result.checks.filter((check) => !check.ok).map((check) => `${check.name}: ${check.detail}`),
      trace_file: traceFile,
    })),
    stats_final: stats,
    result: failed.length === 0 ? "PASSED" : "FAILED",
  };
  const summaryPath = path.join(tracesDir, "demo-summary.json");
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2) + "\n", "utf8");
  console.log(`summary           = ${path.relative(process.cwd(), summaryPath)}`);

  if (failed.length > 0) {
    console.log("");
    console.log(`DEMO RESULT: FAILED (${failed.length} check(s))`);
    return 1;
  }
  console.log("");
  console.log("DEMO RESULT: PASSED");
  return 0;
}

main()
  .then((code) => process.exit(code))
  .catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  });
