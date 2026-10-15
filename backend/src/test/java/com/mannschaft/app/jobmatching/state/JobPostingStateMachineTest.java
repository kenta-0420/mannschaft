package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
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
 * {@link JobPostingStateMachine} のユニットテスト。
 */
@DisplayName("JobPostingStateMachine ユニットテスト")
class JobPostingStateMachineTest {

    private final JobPostingStateMachine stateMachine = new JobPostingStateMachine();

    @Nested
    @DisplayName("正常系: 許容される遷移")
    class ValidTransitions {

        @Test
        @DisplayName("DRAFT → OPEN は許容される")
        void draft_to_open_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobPostingStatus.DRAFT, JobPostingStatus.OPEN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DRAFT → CANCELLED は許容される")
        void draft_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobPostingStatus.DRAFT, JobPostingStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPEN → CLOSED は許容される（定員充足・締切通過）")
        void open_to_closed_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobPostingStatus.OPEN, JobPostingStatus.CLOSED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPEN → CANCELLED は許容される")
        void open_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobPostingStatus.OPEN, JobPostingStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("異常系: 不正遷移で JOB_INVALID_STATE_TRANSITION")
    class InvalidTransitions {

        @Test
        @DisplayName("DRAFT → CLOSED（中間を飛ばす）は例外")
        void draft_to_closed_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobPostingStatus.DRAFT, JobPostingStatus.CLOSED))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("OPEN → DRAFT（逆行）は例外")
        void open_to_draft_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobPostingStatus.OPEN, JobPostingStatus.DRAFT))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("CLOSED → 任意の状態（終着）は全て例外")
        void closed_terminal_ng() {
            for (JobPostingStatus to : JobPostingStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobPostingStatus.CLOSED, to))
                        .as("CLOSED → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("CANCELLED → 任意の状態（終着）は全て例外")
        void cancelled_terminal_ng() {
            for (JobPostingStatus to : JobPostingStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobPostingStatus.CANCELLED, to))
                        .as("CANCELLED → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @ParameterizedTest
        @EnumSource(JobPostingStatus.class)
        @DisplayName("同状態への自己遷移は例外")
        void 自己遷移_ng(JobPostingStatus status) {
            assertThatThrownBy(() -> stateMachine.validate(status, status))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("境界: null 引数の取り扱い")
    class NullHandling {

        @Test
        @DisplayName("from が null の場合 isValidTransition は false")
        void from_null_false() {
            assertThat(stateMachine.isValidTransition(null, JobPostingStatus.OPEN)).isFalse();
        }

        @Test
        @DisplayName("to が null の場合 isValidTransition は false")
        void to_null_false() {
            assertThat(stateMachine.isValidTransition(JobPostingStatus.DRAFT, null)).isFalse();
        }
    }
}
