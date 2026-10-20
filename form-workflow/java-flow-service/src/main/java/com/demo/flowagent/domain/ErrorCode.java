package com.demo.flowagent.domain;

public enum ErrorCode {
    BAD_REQUEST(400),
    UNAUTHENTICATED(401),
    FORBIDDEN_ROLE(403),
    NOT_FOUND(404),
    INVALID_TRANSITION(409),
    STALE_EVENT(409),
    VERSION_MISMATCH(409),
    NOT_APPROVED(409),
    PRECONDITION_FAILED(409),
    PLAN_INVALID(422),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
