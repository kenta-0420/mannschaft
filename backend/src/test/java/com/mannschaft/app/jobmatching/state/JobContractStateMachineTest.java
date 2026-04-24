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
 * <p>Phase 13.1.2 で QR チェックイン／アウトの遷移が追加されたため、
 * {@code MATCHED → CHECKED_IN → IN_PROGRESS → CHECKED_OUT → COMPLETION_REPORTED}
 * の直線フローの正常系と、各中間状態からの {@code CANCELLED}・差し戻し両パターン
 * （{@code COMPLETION_REPORTED → MATCHED} および {@code COMPLETION_REPORTED → IN_PROGRESS}）
 * を網羅検証する。</p>
 *
 * <p>Phase 13.1.4 の ORG_CONFIRM 方式（{@code MATCHED → TIME_CONFIRMED → COMPLETION_REPORTED}）の
 * 遷移も先行定義されているため、合わせて正常系テストを持つ。</p>
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
        @DisplayName("Phase 13.1.2: MATCHED → CHECKED_IN は許容される（IN-QR スキャン）")
        void matched_to_checkedIn_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.CHECKED_IN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: CHECKED_IN → IN_PROGRESS は許容される（自動遷移）")
        void checkedIn_to_inProgress_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.CHECKED_IN, JobContractStatus.IN_PROGRESS))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: CHECKED_IN → CANCELLED は許容される")
        void checkedIn_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.CHECKED_IN, JobContractStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: IN_PROGRESS → CHECKED_OUT は許容される（OUT-QR スキャン）")
        void inProgress_to_checkedOut_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.IN_PROGRESS, JobContractStatus.CHECKED_OUT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: IN_PROGRESS → CANCELLED は許容される（業務中断）")
        void inProgress_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.IN_PROGRESS, JobContractStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: CHECKED_OUT → COMPLETION_REPORTED は許容される（完了報告）")
        void checkedOut_to_completionReported_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.CHECKED_OUT, JobContractStatus.COMPLETION_REPORTED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: CHECKED_OUT → CANCELLED は許容される")
        void checkedOut_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.CHECKED_OUT, JobContractStatus.CANCELLED))
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
        @DisplayName("COMPLETION_REPORTED → MATCHED は許容される（差し戻し・MVP 方式）")
        void completionReported_to_matched_ok_差戻し() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.MATCHED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.2: COMPLETION_REPORTED → IN_PROGRESS は許容される（差し戻し・再チェックイン不要）")
        void completionReported_to_inProgress_ok_差戻し() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.IN_PROGRESS))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("COMPLETION_REPORTED → CANCELLED は許容される")
        void completionReported_to_cancelled_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.4 先行定義: MATCHED → TIME_CONFIRMED は許容される（ORG_CONFIRM 方式）")
        void matched_to_timeConfirmed_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.TIME_CONFIRMED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Phase 13.1.4 先行定義: TIME_CONFIRMED → COMPLETION_REPORTED は許容される")
        void timeConfirmed_to_completionReported_ok() {
            assertThatCode(() -> stateMachine.validate(
                    JobContractStatus.TIME_CONFIRMED, JobContractStatus.COMPLETION_REPORTED))
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
        @DisplayName("CHECKED_IN → CHECKED_OUT（IN_PROGRESS をスキップ）は例外")
        void checkedIn_to_checkedOut_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.CHECKED_IN, JobContractStatus.CHECKED_OUT))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("MATCHED → IN_PROGRESS（CHECKED_IN をスキップ）は例外")
        void matched_to_inProgress_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.IN_PROGRESS))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("MATCHED → CHECKED_OUT（2 段階飛ばし）は例外")
        void matched_to_checkedOut_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.CHECKED_OUT))
                    .isInstanceOf(BusinessException.class);
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
        @DisplayName("Phase 13.1.3 以降の状態（AUTHORIZED 等）への現時点遷移は例外")
        void 未対応状態_ng() {
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.MATCHED, JobContractStatus.AUTHORIZED))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> stateMachine.validate(
                    JobContractStatus.COMPLETION_REPORTED, JobContractStatus.AUTHORIZED))
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
