package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.RunStatus;

public record SubmitView(String runId, RunStatus status, boolean deduplicated) {
}
