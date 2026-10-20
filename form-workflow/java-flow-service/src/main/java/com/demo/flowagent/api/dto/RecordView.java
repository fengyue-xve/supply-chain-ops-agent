package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.FlowType;

import java.time.Instant;

public record RecordView(
        String recordId,
        FlowType flowType,
        int businessVersion,
        String skuCode,
        String markRule,
        MarkView outerBoxMark,
        Instant updatedAt) {

    public record MarkView(String value, String ruleSource, Instant updatedAt) {
    }
}
