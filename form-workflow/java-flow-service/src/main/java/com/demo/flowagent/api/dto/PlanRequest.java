package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.FlowType;
import com.demo.flowagent.domain.Risk;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlanRequest(
        @NotBlank String planVersion,
        @NotBlank @Pattern(regexp = "^R-[0-9]{4}$") String sourceRecordId,
        @NotNull FlowType flowType,
        @NotNull @Min(1) Integer businessVersion,
        @NotBlank String targetField,
        @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,12}$") String proposedValue,
        @NotBlank @Pattern(regexp = "^fixture-rule:[a-z0-9-]{2,64}$") String ruleSource,
        @NotNull @Size(min = 1) List<@Valid PreconditionRequest> preconditions,
        @NotBlank @Pattern(regexp = "^FLOW_IDK_[0-9a-f]{16}$") String idempotencyKey,
        @NotNull Risk risk,
        @NotNull Boolean requiresHumanApproval) {
}
