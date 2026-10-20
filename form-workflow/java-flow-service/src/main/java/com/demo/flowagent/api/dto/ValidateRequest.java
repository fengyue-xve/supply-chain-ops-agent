package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.FlowType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ValidateRequest(
        @NotBlank @Pattern(regexp = "^R-[0-9]{4}$") String sourceRecordId,
        @NotNull FlowType flowType,
        @NotNull @Min(1) Integer businessVersion) {
}
