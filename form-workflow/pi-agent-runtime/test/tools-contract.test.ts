// Bidirectional contract test: the Node-side tool registry and route table must
// match contracts/tools.schema.json exactly (names, params, method+path), and
// plan_transition must mirror contracts/plan.schema.json plus the run_id path
// param. The Java side carries the mirror-image assertions.

import test from "node:test";
import assert from "node:assert/strict";
import { createFlowTools } from "../src/tools.ts";
import { Trace } from "../src/trace.ts";
import { FLOW_ROUTES, type FlowApi } from "../src/java-client.ts";
import { loadContractJson } from "../src/config.ts";

type JsonSchema = { [key: string]: any };

interface RouteEntry {
  method: string;
  path: string;
  params?: JsonSchema;
  params_schema?: string;
}

const contract = loadContractJson("tools.schema.json") as unknown as {
  agent_tools: Record<string, RouteEntry>;
  human_actions: Record<string, RouteEntry>;
  observability: Record<string, RouteEntry>;
};
const planSchema = loadContractJson("plan.schema.json") as unknown as JsonSchema;

const stubApi = new Proxy(
  {},
  { get: () => () => Promise.reject(new Error("contract test must not call Java")) },
) as unknown as FlowApi;
const tools = createFlowTools({
  api: stubApi,
  trace: new Trace("contract", "contract"),
  phase: () => "contract",
  state: {},
});
const toolByName = new Map(tools.map((tool) => [tool.name, tool]));

function literalValues(schema: JsonSchema): unknown[] {
  if (schema.const !== undefined) return [schema.const];
  if (Array.isArray(schema.anyOf)) return schema.anyOf.flatMap((part: JsonSchema) => literalValues(part));
  if (Array.isArray(schema.enum)) return schema.enum;
  throw new Error(`schema is not a literal/enum: ${JSON.stringify(schema)}`);
}

function pathParams(path: string): string[] {
  return [...path.matchAll(/\{(\w+)\}/g)].map((match) => match[1]);
}

function contractProperties(entry: RouteEntry): JsonSchema {
  if (entry.params_schema) {
    return (loadContractJson(entry.params_schema) as unknown as JsonSchema).properties ?? {};
  }
  return entry.params?.properties ?? {};
}

function contractRequired(entry: RouteEntry): string[] {
  if (entry.params_schema) {
    return (loadContractJson(entry.params_schema) as unknown as JsonSchema).required ?? [];
  }
  return entry.params?.required ?? [];
}

function compareLiteralConstraints(label: string, ours: JsonSchema, theirs: JsonSchema): void {
  for (const key of ["type", "pattern", "minimum", "maxLength", "minLength"] as const) {
    if (theirs[key] !== undefined) assert.equal(ours[key], theirs[key], `${label}.${key}`);
  }
  if (theirs.const !== undefined || theirs.enum !== undefined || theirs.anyOf !== undefined) {
    assert.deepEqual(literalValues(ours), literalValues(theirs), `${label} literal values`);
  }
}

test("agent tool registry equals contract agent_tools exactly; human actions are not tools", () => {
  assert.deepEqual(
    tools.map((tool) => tool.name).sort(),
    Object.keys(contract.agent_tools).sort(),
  );
  const names = new Set(tools.map((tool) => tool.name));
  for (const human of Object.keys(contract.human_actions)) {
    assert.ok(!names.has(human), `${human} must not be exposed as an agent tool`);
  }
});

test("each tool's params mirror contract properties/required plus path params", () => {
  for (const [name, entry] of Object.entries(contract.agent_tools)) {
    const tool = toolByName.get(name);
    assert.ok(tool, `tool ${name} missing`);
    const schema = tool.parameters as JsonSchema;
    const expectedKeys = new Set([...Object.keys(contractProperties(entry)), ...pathParams(entry.path)]);
    assert.deepEqual(Object.keys(schema.properties).sort(), [...expectedKeys].sort(), `${name} properties`);
    const expectedRequired = new Set([...contractRequired(entry), ...pathParams(entry.path)]);
    assert.deepEqual([...(schema.required ?? [])].sort(), [...expectedRequired].sort(), `${name} required`);
    assert.equal(schema.additionalProperties, false, `${name} additionalProperties`);
  }
});

test("per-property scalar constraints and literal values match the contract", () => {
  for (const [name, entry] of Object.entries(contract.agent_tools)) {
    const schema = toolByName.get(name)!.parameters as JsonSchema;
    for (const [key, theirProp] of Object.entries(contractProperties(entry)) as [string, JsonSchema][]) {
      compareLiteralConstraints(`${name}.${key}`, schema.properties[key], theirProp);
    }
  }
});

test("plan_transition preconditions mirror plan.schema.json $defs.precondition", () => {
  const schema = toolByName.get("plan_transition")!.parameters as JsonSchema;
  const ours = schema.properties.preconditions;
  const theirs = planSchema.properties.preconditions;
  assert.equal(ours.type, "array");
  assert.equal(ours.minItems, theirs.minItems);
  const def = planSchema.$defs.precondition as JsonSchema;
  assert.equal(ours.items.type, "object");
  assert.equal(ours.items.additionalProperties, def.additionalProperties);
  assert.deepEqual([...ours.items.required].sort(), [...def.required].sort());
  assert.deepEqual(literalValues(ours.items.properties.kind), literalValues(def.properties.kind));
});

test("FLOW_ROUTES is bidirectionally locked to contract method+path (11 routes)", () => {
  const expected = new Map<string, { method: string; path: string }>();
  for (const section of [contract.agent_tools, contract.human_actions, contract.observability]) {
    for (const [name, entry] of Object.entries(section)) {
      expected.set(name, { method: entry.method, path: entry.path });
    }
  }
  assert.equal(expected.size, 11);
  assert.deepEqual(Object.keys(FLOW_ROUTES).sort(), [...expected.keys()].sort());
  for (const [name, route] of Object.entries(FLOW_ROUTES)) {
    assert.deepEqual(route, expected.get(name), name);
  }
});
