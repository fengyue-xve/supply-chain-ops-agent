import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

export const runtimeRoot = path.resolve(here, "..");
export const projectRoot = path.resolve(runtimeRoot, "..");
export const contractsDir = path.join(projectRoot, "contracts");
export const fixturesDir = path.join(projectRoot, "fixtures");

export const javaBaseUrl = process.env.FLOW_JAVA_BASE_URL ?? "http://127.0.0.1:8081";
export const tracesDir = process.env.FLOW_TRACES_DIR ?? path.join(runtimeRoot, "traces");

export function loadJson<T = unknown>(filePath: string): T {
  return JSON.parse(fs.readFileSync(filePath, "utf8")) as T;
}

export interface FixtureUser {
  actor_id: string;
  role: "operator" | "approver" | "agent" | "reader";
}

export function loadUsers(): Record<string, FixtureUser> {
  return loadJson<{ users: Record<string, FixtureUser> }>(path.join(fixturesDir, "users.json")).users;
}

export function tokenForRole(role: FixtureUser["role"]): string {
  const users = loadUsers();
  for (const [token, user] of Object.entries(users)) {
    if (user.role === role) return token;
  }
  throw new Error(`no fixture token for role ${role}`);
}

export function actorForRole(role: FixtureUser["role"]): string {
  const users = loadUsers();
  for (const user of Object.values(users)) {
    if (user.role === role) return user.actor_id;
  }
  throw new Error(`no fixture actor for role ${role}`);
}

export interface FixtureRecord {
  record_id: string;
  flow_type: string;
  business_version: number;
  sku_code: string;
  mark_rule: string;
  current_mark: { value: string; rule_source: string } | null;
  demo_fault: { kind: string; remaining: number | null } | null;
}

export interface FixtureEvent {
  event_id: string;
  source_record_id: string;
  flow_type: string;
  business_version: number;
  submitted_by: string;
  note: string | null;
}

export interface RuleEntry {
  proposed_value: string;
  rule_source: string;
}

export function loadFixtureRecords(): FixtureRecord[] {
  return loadJson<{ records: FixtureRecord[] }>(path.join(fixturesDir, "records.json")).records;
}

export function loadFixtureEvents(): FixtureEvent[] {
  return loadJson<{ events: FixtureEvent[] }>(path.join(fixturesDir, "events.json")).events;
}

export function loadRules(): Record<string, RuleEntry> {
  return loadJson<{ rules: Record<string, RuleEntry> }>(path.join(fixturesDir, "rules.json")).rules;
}

export function loadContractJson(name: string): Record<string, unknown> {
  return loadJson<Record<string, unknown>>(path.join(contractsDir, name));
}
