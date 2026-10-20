package com.demo.flowagent.domain;

import java.time.Instant;

public record AuditRecord(
        long id,
        String runId,
        Instant ts,
        AuditEventType eventType,
        String actorId,
        String detailJson) {
}
