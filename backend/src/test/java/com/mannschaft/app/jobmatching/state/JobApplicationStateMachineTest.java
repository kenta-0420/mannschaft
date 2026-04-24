package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JobApplicationStateMachine} のユニットテスト。
 */
@DisplayName("JobApplicationStateMachine ユニットテスト")
class JobApplicationStateMachineTest {

    private final JobApplicationStateMachine stateMachine = new JobApplicationStateMachine();

    @Nested
    @DisplayName("正常系: 許容される遷移")
    class ValidTransitions {

        @Test
        @DisplayName("APPLIED → ACCEPTED は許容される")
        void applied_to_accepted_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobApplicationStatus.APPLIED, JobApplicationStatus.ACCEPTED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("APPLIED → REJECTED は許容される")
        void applied_to_rejected_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobApplicationStatus.APPLIED, JobApplicationStatus.REJECTED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("APPLIED → WITHDRAWN は許容される")
        void applied_to_withdrawn_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobApplicationStatus.APPLIED, JobApplicationStatus.WITHDRAWN))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("異常系: 不正遷移で JOB_INVALID_STATE_TRANSITION")
    class InvalidTransitions {

        @Test
        @DisplayName("ACCEPTED → REJECTED（終着から）は例外")
        void accepted_terminal_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobApplicationStatus.ACCEPTED, JobApplicationStatus.REJECTED))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("REJECTED → 任意（終着）は例外")
        void rejected_terminal_ng() {
            for (JobApplicationStatus to : JobApplicationStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobApplicationStatus.REJECTED, to))
                        .as("REJECTED → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("WITHDRAWN → 任意（終着）は例外")
        void withdrawn_terminal_ng() {
            for (JobApplicationStatus to : JobApplicationStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobApplicationStatus.WITHDRAWN, to))
                        .as("WITHDRAWN → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @ParameterizedTest
        @EnumSource(JobApplicationStatus.class)
        @DisplayName("同状態への自己遷移は例外")
        void 自己遷移_ng(JobApplicationStatus status) {
            assertThatThrownBy(() -> stateMachine.validate(status, status))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("境界: null 引数")
    class NullHandling {

        @Test
        @DisplayName("from/to どちらかが null で isValidTransition は false")
        void null_false() {
            assertThat(stateMachine.isValidTransition(null, JobApplicationStatus.ACCEPTED)).isFalse();
            assertThat(stateMachine.isValidTransition(JobApplicationStatus.APPLIED, null)).isFalse();
        }
    }
}
