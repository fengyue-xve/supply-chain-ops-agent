// Idempotency derivation must match fixtures/idempotency-vectors.json — the same
// vectors are asserted on the Java side (IdempotencyKeys), locking both impls.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { deriveIdempotencyKey, recordRef } from "../src/idem.ts";
import { fixturesDir } from "../src/config.ts";

interface IdemVector {
  source_record_id: string;
  flow_type: string;
  business_version: number;
  idempotency_key: string;
  record_ref: string;
}

const vectors = (
  JSON.parse(fs.readFileSync(path.join(fixturesDir, "idempotency-vectors.json"), "utf8")) as {
    vectors: IdemVector[];
  }
).vectors;

test("all fixture vectors derive identically on the Node side", () => {
  assert.ok(vectors.length >= 7, "expected at least 7 shared vectors");
  for (const vector of vectors) {
    assert.equal(
      deriveIdempotencyKey(vector.source_record_id, vector.flow_type, vector.business_version),
      vector.idempotency_key,
      `idempotency key for ${vector.source_record_id} v${vector.business_version}`,
    );
    assert.equal(recordRef(vector.source_record_id), vector.record_ref, `record_ref for ${vector.source_record_id}`);
  }
});

test("keys are shape-valid and unique per (record, flow_type, version)", () => {
  const keys = new Set<string>();
  for (const vector of vectors) {
    const key = deriveIdempotencyKey(vector.source_record_id, vector.flow_type, vector.business_version);
    assert.match(key, /^FLOW_IDK_[0-9a-f]{16}$/);
    assert.ok(!keys.has(key), `duplicate key ${key}`);
    keys.add(key);
    assert.match(recordRef(vector.source_record_id), /^[0-9a-f]{12}$/);
  }
});

test("key changes with business_version and flow_type, not only the record", () => {
  const base = deriveIdempotencyKey("R-1001", "PRELOAD_OUTER_BOX_MARK", 3);
  assert.notEqual(deriveIdempotencyKey("R-1001", "PRELOAD_OUTER_BOX_MARK", 4), base);
  assert.notEqual(deriveIdempotencyKey("R-1001", "OTHER_FLOW", 3), base);
  assert.notEqual(deriveIdempotencyKey("R-1002", "PRELOAD_OUTER_BOX_MARK", 3), base);
});
