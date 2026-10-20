package com.demo.flowagent.domain;

import java.util.Map;

public class FlowException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public FlowException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public FlowException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
