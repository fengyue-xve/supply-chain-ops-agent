package com.demo.flowagent.engine;

import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全量 9x9 迁移矩阵对照 kickoff 状态机；非法迁移必须抛 INVALID_TRANSITION。 */
class StateMachineTest {

    private static final Map<RunStatus, Set<RunStatus>> EXPECTED = Map.of(
            RunStatus.RECEIVED, Set.of(RunStatus.PLANNED),
            RunStatus.PLANNED, Set.of(RunStatus.WAITING_APPROVAL),
            RunStatus.WAITING_APPROVAL, Set.of(RunStatus.EXECUTING, RunStatus.REJECTED),
            RunStatus.EXECUTING, Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED),
            RunStatus.SUCCEEDED, Set.of(),
            RunStatus.REJECTED, Set.of(),
            RunStatus.FAILED, Set.of(RunStatus.MANUAL_REVIEW),
            RunStatus.MANUAL_REVIEW, Set.of(RunStatus.REPLAYING),
            RunStatus.REPLAYING, Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED));

    @Test
    void acceptsExactlyTheContractMatrixForAllPairs() {
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                boolean expected = EXPECTED.getOrDefault(from, Set.of()).contains(to);
                assertThat(StateMachine.allows(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
                if (expected) {
                    StateMachine.require(from, to);
                } else {
                    assertThatThrownBy(() -> StateMachine.require(from, to))
                            .isInstanceOf(FlowException.class)
                            .extracting(e -> ((FlowException) e).code())
                            .isEqualTo(ErrorCode.INVALID_TRANSITION);
                }
            }
        }
    }

    @Test
    void terminalStatesAreTerminal() {
        for (RunStatus to : RunStatus.values()) {
            assertThat(StateMachine.allows(RunStatus.SUCCEEDED, to)).isFalse();
            assertThat(StateMachine.allows(RunStatus.REJECTED, to)).isFalse();
        }
    }

    @Test
    void noShortcutIntoExecutingOrSuccess() {
        assertThat(StateMachine.allows(RunStatus.RECEIVED, RunStatus.EXECUTING)).isFalse();
        assertThat(StateMachine.allows(RunStatus.RECEIVED, RunStatus.SUCCEEDED)).isFalse();
        assertThat(StateMachine.allows(RunStatus.PLANNED, RunStatus.EXECUTING)).isFalse();
        assertThat(StateMachine.allows(RunStatus.WAITING_APPROVAL, RunStatus.SUCCEEDED)).isFalse();
        assertThat(StateMachine.allows(RunStatus.MANUAL_REVIEW, RunStatus.EXECUTING)).isFalse();
    }
}
