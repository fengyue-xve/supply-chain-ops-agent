package com.demo.flowagent.api.dto;

import java.util.Map;

public record StatsView(
        int runCount,
        Map<String, Integer> runsByStatus,
        long totalWriteCount,
        long totalSkipCount,
        long totalManualReview,
        long applyEndpointHits,
        long replayEndpointHits) {
}
