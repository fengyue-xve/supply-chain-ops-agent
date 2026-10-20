package com.demo.flowagent.domain;

public enum RunStatus {
    RECEIVED,
    PLANNED,
    WAITING_APPROVAL,
    EXECUTING,
    SUCCEEDED,
    REJECTED,
    FAILED,
    MANUAL_REVIEW,
    REPLAYING
}
