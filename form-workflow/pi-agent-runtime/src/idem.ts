import { createHash } from "node:crypto";

// Derivation locked with Java com.demo.flowagent.engine.IdempotencyKeys.
// Shared vectors: fixtures/idempotency-vectors.json (both sides tested against it).
export const IDEMPOTENCY_KEY_PREFIX = "FLOW_IDK_";

export function deriveIdempotencyKey(sourceRecordId: string, flowType: string, businessVersion: number): string {
  const digest = createHash("sha256").update(`${sourceRecordId}:${flowType}:${businessVersion}`).digest("hex");
  return IDEMPOTENCY_KEY_PREFIX + digest.slice(0, 16);
}

export function recordRef(sourceRecordId: string): string {
  return createHash("sha256").update(sourceRecordId).digest("hex").slice(0, 12);
}
