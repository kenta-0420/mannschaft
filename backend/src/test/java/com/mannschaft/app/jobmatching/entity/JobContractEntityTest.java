package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JobContractEntity} のエンティティ単体テスト。
 *
 * <p>Phase 13.1.2 で追加されたチェックイン／アウト時刻と業務時間（分）の
 * フィールド更新・duration 計算のロジックを検証する。</p>
 */
@DisplayName("JobContractEntity 単体テスト")
class JobContractEntityTest {

    private JobContractEntity newContract() {
        return JobContractEntity.builder()
                .jobPostingId(1L)
                .jobApplicationId(2L)
                .requesterUserId(10L)
                .workerUserId(20L)
                .baseRewardJpy(5000)
                .workStartAt(LocalDateTime.of(2026, 4, 24, 10, 0))
                .workEndAt(LocalDateTime.of(2026, 4, 24, 18, 0))
                .status(JobContractStatus.MATCHED)
                .matchedAt(LocalDateTime.of(2026, 4, 23, 9, 0))
                .rejectionCount(0)
                .version(0)
                .build();
    }

    @Nested
    @DisplayName("recordCheckIn")
    class RecordCheckIn {

        @Test
        @DisplayName("checkedInAt のみ更新され、他のフィールドや status は変更されない")
        void checkedInAtのみ更新され_他フィールドやstatusは変更されない() {
            // Given
            JobContractEntity contract = newContract();
            Instant at = Instant.parse("2026-04-24T10:00:05.000Z");

            // When
            contract.recordCheckIn(at);

            // Then
            assertThat(contract.getCheckedInAt()).isEqualTo(at);
            assertThat(contract.getCheckedOutAt()).isNull();
            assertThat(contract.getWorkDurationMinutes()).isNull();
            // status 遷移は StateMachine 担当なのでここでは変更されないことを確認
            assertThat(contract.getStatus()).isEqualTo(JobContractStatus.MATCHED);
        }
    }

    @Nested
    @DisplayName("recordCheckOut")
    class RecordCheckOut {

        @Test
        @DisplayName("duration が整数分で計算される（8 時間ちょうど → 480 分）")
        void duration_8時間ちょうど_480分() {
            // Given
            JobContractEntity contract = newContract();
            contract.recordCheckIn(Instant.parse("2026-04-24T10:00:00.000Z"));

            // When
            contract.recordCheckOut(Instant.parse("2026-04-24T18:00:00.000Z"));

            // Then
            assertThat(contract.getCheckedOutAt()).isEqualTo(Instant.parse("2026-04-24T18:00:00.000Z"));
            assertThat(contract.getWorkDurationMinutes()).isEqualTo(480);
        }

        @Test
        @DisplayName("分未満（59 秒）は切り捨てられ 0 分になる")
        void 分未満は切り捨てられ0分() {
            // Given
            JobContractEntity contract = newContract();
            contract.recordCheckIn(Instant.parse("2026-04-24T10:00:00.000Z"));

            // When
            contract.recordCheckOut(Instant.parse("2026-04-24T10:00:59.000Z"));

            // Then
            assertThat(contract.getWorkDurationMinutes()).isEqualTo(0);
        }

        @Test
        @DisplayName("端数（1 時間 30 分 59 秒）は分未満切り捨てで 90 分になる")
        void 端数1時間30分59秒は90分() {
            // Given
            JobContractEntity contract = newContract();
            contract.recordCheckIn(Instant.parse("2026-04-24T10:00:00.000Z"));

            // When
            contract.recordCheckOut(Instant.parse("2026-04-24T11:30:59.000Z"));

            // Then
            assertThat(contract.getWorkDurationMinutes()).isEqualTo(90);
        }

        @Test
        @DisplayName("ちょうど 1 分（60 秒）は 1 分として記録される")
        void ちょうど60秒は1分() {
            // Given
            JobContractEntity contract = newContract();
            contract.recordCheckIn(Instant.parse("2026-04-24T10:00:00.000Z"));

            // When
            contract.recordCheckOut(Instant.parse("2026-04-24T10:01:00.000Z"));

            // Then
            assertThat(contract.getWorkDurationMinutes()).isEqualTo(1);
        }

        @Test
        @DisplayName("checkedInAt 未設定のまま呼び出すと IllegalStateException")
        void checkedInAt未設定だとIllegalStateException() {
            // Given
            JobContractEntity contract = newContract();

            // When / Then
            assertThatThrownBy(() ->
                    contract.recordCheckOut(Instant.parse("2026-04-24T18:00:00.000Z")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("checkedInAt");
        }
    }
}
