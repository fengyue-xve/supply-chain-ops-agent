package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.ApplyOutcome;
import com.demo.flowagent.domain.ApplyResult;
import com.demo.flowagent.domain.RunStatus;

public record ApplyOutcomeView(ApplyOutcome outcome, ApplyRunView run, String message) {

    public record ApplyRunView(String runId, RunStatus status, ApplyResult applyResult, int writeCount) {
    }
}
