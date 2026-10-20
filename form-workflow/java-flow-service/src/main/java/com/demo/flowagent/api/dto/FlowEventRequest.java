package com.demo.flowagent.api.dto;

import com.demo.flowagent.domain.FlowType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FlowEventRequest(
        @NotBlank @Pattern(regexp = "^evt-[0-9]{3,6}$") String eventId,
        @NotBlank @Pattern(regexp = "^R-[0-9]{4}$") String sourceRecordId,
        @NotNull FlowType flowType,
        @NotNull @Min(1) @Max(1_000_000) Integer businessVersion,
        @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,32}$") String submittedBy,
        @Size(max = 200) String note) {
}
