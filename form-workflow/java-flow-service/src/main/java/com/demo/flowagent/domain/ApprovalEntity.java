package com.demo.flowagent.domain;

import java.time.Instant;

public record ApprovalEntity(
        String runId,
        ApprovalDecision decision,
        String actorId,
        String note,
        Instant decidedAt) {
}
