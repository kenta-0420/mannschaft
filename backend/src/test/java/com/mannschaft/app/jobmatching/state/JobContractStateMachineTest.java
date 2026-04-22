package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
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
 * {@link JobContractStateMachine} のユニットテスト。
 *
 * <p>MVP の状態遷移表に基づき、許容される全遷移の正常系と、
 * それ以外の組み合わせで {@link BusinessException}（JOB_INVALID_STATE_TRANSITION）
 * が投げられることを網羅検証する。</p>
 */
@DisplayName("JobContractStateMachine ユニットテスト")
class JobContractStateMachineTest {

    private final JobContractStateMachine stateMachine = new JobContractStateMachine();

    @Nested
    @DisplayName("正常系: 許容される遷移")
    class ValidTransitions {

        @Test
        @DisplayName("MATCHED → COMPLETION_REPORTED は許容される")
        void matched_to_completionReported_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.COMPLETION_REPORTED))
                    .doesNotThrowAnyException();
            assertThat(stateMachine.isValidTransition(
                    JobContractStatus.MATCHED, JobContractStatus.COMPLETION_REPORTED)).isTrue();
        }

        @Test
        @DisplayName("MATCHED → CANCELLED は許容される")
        void matched_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("COMPLETION_REPORTED → COMPLETED は許容される")
        void completionReported_to_completed_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.COMPLETED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("COMPLETION_REPORTED → MATCHED は許容される（差し戻し）")
        void completionReported_to_matched_ok_差戻し() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.MATCHED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("COMPLETION_REPORTED → CANCELLED は許容される")
        void completionReported_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("異常系: 不正遷移で JOB_INVALID_STATE_TRANSITION")
    class InvalidTransitions {

        @Test
        @DisplayName("MATCHED → COMPLETED（中間状態を飛ばす）は例外")
        void matched_to_completed_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.COMPLETED))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("COMPLETED → 任意の状態（終着からの遷移）は全て例外")
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void completed_terminal_ng() {
            for (JobContractStatus to : JobContractStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobContractStatus.COMPLETED, to))
                        .as("COMPLETED → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("CANCELLED → 任意の状態（終着）は全て例外")
        void cancelled_terminal_ng() {
            for (JobContractStatus to : JobContractStatus.values()) {
                assertThatThrownBy(() -> stateMachine.validate(JobContractStatus.CANCELLED, to))
                        .as("CANCELLED → %s", to)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @ParameterizedTest
        @EnumSource(value = JobContractStatus.class, names = {"MATCHED", "CHECKED_IN", "IN_PROGRESS", "CHECKED_OUT", "TIME_CONFIRMED", "COMPLETION_REPORTED", "COMPLETED", "AUTHORIZED", "CAPTURED", "PAID", "CANCELLED", "DISPUTED"})
        @DisplayName("同状態への自己遷移は例外")
        void 自己遷移_ng(JobContractStatus status) {
            assertThatThrownBy(() -> stateMachine.validate(status, status))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Phase 13.1.2 以降の状態（CHECKED_IN 等）への MVP 遷移は例外")
        void mvp_未対応状態_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.CHECKED_IN))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.AUTHORIZED))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("境界: null 引数の取り扱い")
    class NullHandling {

        @Test
        @DisplayName("from が null の場合 isValidTransition は false")
        void from_null_false() {
            assertThat(stateMachine.isValidTransition(null, JobContractStatus.MATCHED)).isFalse();
        }

        @Test
        @DisplayName("to が null の場合 isValidTransition は false")
        void to_null_false() {
            assertThat(stateMachine.isValidTransition(JobContractStatus.MATCHED, null)).isFalse();
        }

        @Test
        @DisplayName("validate に null を渡すと例外（isValidTransition が false → validate が例外）")
        void validate_null_例外() {
            assertThatThrownBy(() -> stateMachine.validate(null, JobContractStatus.MATCHED))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
