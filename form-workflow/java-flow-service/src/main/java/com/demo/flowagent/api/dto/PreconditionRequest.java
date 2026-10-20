package com.demo.flowagent.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record PreconditionRequest(
        @NotBlank String kind,
        Map<String, Object> detail) {
}
