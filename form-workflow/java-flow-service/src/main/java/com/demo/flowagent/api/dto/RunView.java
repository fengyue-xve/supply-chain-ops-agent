package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.ApplyResult;
import com.demo.flowagent.domain.ApprovalDecision;
import com.demo.flowagent.domain.AuditEventType;
import com.demo.flowagent.domain.FlowType;
import com.demo.flowagent.domain.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record RunView(
        String runId,
        String sourceRecordId,
        FlowType flowType,
        int businessVersion,
        String idempotencyKey,
        RunStatus status,
        ApplyResult applyResult,
        JsonNode plan,
        ApprovalView approval,
        String failureReason,
        int writeCount,
        int replayCount,
        Instant createdAt,
        Instant updatedAt,
        List<AuditEntryView> audit) {

    public record ApprovalView(ApprovalDecision decision, String actorId, Instant decidedAt, String note) {
    }

    public record AuditEntryView(long seq, Instant ts, AuditEventType eventType, String actorId, JsonNode detail) {
    }
}
