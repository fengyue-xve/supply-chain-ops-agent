package com.demo.flowagent.domain;

import java.time.Instant;

public record FormRecord(
        String recordId,
        FlowType flowType,
        int businessVersion,
        String skuCode,
        String markRule,
        String markValue,
        String markRuleSource,
        Instant markUpdatedAt,
        String demoFault,
        Integer demoFaultRemaining,
        Instant updatedAt) {
}
