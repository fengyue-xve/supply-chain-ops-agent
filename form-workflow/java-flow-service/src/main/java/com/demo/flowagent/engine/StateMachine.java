package com.demo.flowagent.engine;

import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.domain.RunStatus;

import java.util.Map;
import java.util.Set;

/** 合法迁移表（contracts/README.md 唯一状态字典）。 */
public final class StateMachine {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
            RunStatus.RECEIVED, Set.of(RunStatus.PLANNED),
            RunStatus.PLANNED, Set.of(RunStatus.WAITING_APPROVAL),
            RunStatus.WAITING_APPROVAL, Set.of(RunStatus.EXECUTING, RunStatus.REJECTED),
            RunStatus.EXECUTING, Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED),
            RunStatus.FAILED, Set.of(RunStatus.MANUAL_REVIEW),
            RunStatus.MANUAL_REVIEW, Set.of(RunStatus.REPLAYING),
            RunStatus.REPLAYING, Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED),
            RunStatus.SUCCEEDED, Set.of(),
            RunStatus.REJECTED, Set.of());

    private StateMachine() {
    }

    public static boolean allows(RunStatus from, RunStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(RunStatus from, RunStatus to) {
        if (!allows(from, to)) {
            throw new FlowException(ErrorCode.INVALID_TRANSITION,
                    "非法状态迁移: " + from + " -> " + to,
                    Map.of("current_status", from.name(), "target_status", to.name()));
        }
    }
}
