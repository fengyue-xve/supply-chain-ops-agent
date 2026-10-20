package com.demo.flowagent.domain;

import java.time.Instant;

public record FlowRunEntity(
        String runId,
        String eventId,
        String sourceRecordId,
        FlowType flowType,
        int businessVersion,
        String idempotencyKey,
        RunStatus status,
        ApplyResult applyResult,
        String planJson,
        String failureReason,
        int writeCount,
        int replayCount,
        Instant createdAt,
        Instant updatedAt) {
}
