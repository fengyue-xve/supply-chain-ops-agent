package com.demo.flowagent.api.dto;

import java.util.Map;

public record ErrorView(String errorCode, String message, Map<String, Object> details) {
}
