package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.schedule.ScheduledTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleScheduledTaskEntity} の {@code last_error} 切り詰め契約テスト（AC-25）。
 *
 * <p><b>背景</b>: 保存する失敗理由は例外メッセージ由来で、長さは実行時に決まる。
 * DDL（{@code V72.001__create_schedule_scheduled_tasks.sql}）の
 * {@code last_error VARCHAR(1000)} を超えると
 * {@code Data truncation: Data too long for column 'last_error'} で
 * <b>更新そのものが失敗し、失敗の記録すら残らない</b>（attempt_count も status も進まない）。
 * カラム幅を広げる対処では、より長いメッセージで再発するため根治にならない。</p>
 *
 * <p>本テストは境界値（ちょうどカラム長・カラム長 + 1）で切り詰めの有無を固定する。
 * 実 DB への書き込みが例外なく通ることは
 * {@code ScheduleMaterializeIntegrationTest} の AC-25 ケースが担保する（二段構え）。</p>
 */
@DisplayName("ScheduleScheduledTaskEntity — last_error の切り詰め（AC-25）")
class ScheduleScheduledTaskEntityLastErrorTest {

    /** DDL の VARCHAR(1000) と一致していること自体を固定する。 */
    private static final int COLUMN_LENGTH = 1000;

    private static ScheduleScheduledTaskEntity newTask() {
        return ScheduleScheduledTaskEntity.builder().build();
    }

    private static String repeat(int length) {
        return "E".repeat(length);
    }

    @Test
    @DisplayName("前提: 定数がDDLのカラム長(1000)と一致する")
    void constantMatchesDdl() {
        assertThat(ScheduleScheduledTaskEntity.LAST_ERROR_MAX)
                .as("DDL の last_error VARCHAR(1000) と定数がずれてはならない")
                .isEqualTo(COLUMN_LENGTH);
    }

    @Nested
    @DisplayName("境界値")
    class Boundary {

        @Test
        @DisplayName("AC-25: ちょうどカラム長(1000)は切り詰めず全文を保持する")
        void exactlyColumnLengthIsNotTruncated() {
            String error = repeat(COLUMN_LENGTH);
            ScheduleScheduledTaskEntity task = newTask();

            task.recordFailedAttempt(error, 5);

            assertThat(task.getLastError())
                    .as("AC-25: 桁ちょうどは加工しない（過剰に削らない）")
                    .isEqualTo(error)
                    .hasSize(COLUMN_LENGTH)
                    .doesNotContain(ScheduleScheduledTaskEntity.LAST_ERROR_TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("AC-25: カラム長+1(1001)は切り詰められ、桁に収まる")
        void oneOverColumnLengthIsTruncated() {
            ScheduleScheduledTaskEntity task = newTask();

            task.recordFailedAttempt(repeat(COLUMN_LENGTH + 1), 5);

            assertThat(task.getLastError())
                    .as("AC-25: 1文字でも超えたらカラム長以内に収めること")
                    .hasSize(COLUMN_LENGTH)
                    .endsWith(ScheduleScheduledTaskEntity.LAST_ERROR_TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("AC-25: 極端に長いメッセージでもカラム長以内に収まる")
        void veryLongMessageFitsInColumn() {
            ScheduleScheduledTaskEntity task = newTask();

            task.recordFailedAttempt(repeat(50_000), 5);

            assertThat(task.getLastError()).hasSize(COLUMN_LENGTH);
        }
    }

    @Test
    @DisplayName("AC-25: 切り詰めが起きたことが保存内容から判別できる")
    void truncationIsDetectableFromStoredValue() {
        ScheduleScheduledTaskEntity truncated = newTask();
        truncated.recordFailedAttempt(repeat(COLUMN_LENGTH + 500), 5);

        ScheduleScheduledTaskEntity intact = newTask();
        intact.recordFailedAttempt("短いエラー", 5);

        assertThat(truncated.getLastError())
                .as("全文か途中かを診断時に区別できる印が要る")
                .endsWith(ScheduleScheduledTaskEntity.LAST_ERROR_TRUNCATION_MARKER);
        assertThat(intact.getLastError())
                .doesNotContain(ScheduleScheduledTaskEntity.LAST_ERROR_TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("AC-25: 短いメッセージは全文がそのまま保存される（過剰に削らない）")
    void shortMessageIsStoredVerbatim() {
        ScheduleScheduledTaskEntity task = newTask();

        task.recordFailedAttempt("com.example.BoomException: 失敗しました", 5);

        assertThat(task.getLastError()).isEqualTo("com.example.BoomException: 失敗しました");
    }

    @Test
    @DisplayName("AC-25: null は null のまま（成功時のクリア経路を壊さない）")
    void nullStaysNull() {
        ScheduleScheduledTaskEntity task = newTask();

        task.recordFailedAttempt(null, 5);

        assertThat(task.getLastError()).isNull();
    }

    @Test
    @DisplayName("AC-25: markFailed 経路でも切り詰められる（記録経路を取りこぼさない）")
    void markFailedAlsoTruncates() {
        ScheduleScheduledTaskEntity task = newTask();

        task.markFailed(repeat(COLUMN_LENGTH + 1));

        assertThat(task.getLastError())
                .hasSize(COLUMN_LENGTH)
                .endsWith(ScheduleScheduledTaskEntity.LAST_ERROR_TRUNCATION_MARKER);
        assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.FAILED);
    }

    @Nested
    @DisplayName("status 遷移は切り詰めの影響を受けない")
    class StatusTransition {

        @Test
        @DisplayName("AC-25: 長大なエラーでも MAX_ATTEMPTS 未満は PENDING のまま")
        void belowMaxAttemptsRemainsPending() {
            ScheduleScheduledTaskEntity task = newTask();

            task.recordFailedAttempt(repeat(COLUMN_LENGTH + 1), 5);

            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-25: 長大なエラーでも MAX_ATTEMPTS 到達で FAILED 確定")
        void reachingMaxAttemptsBecomesFailed() {
            ScheduleScheduledTaskEntity task = newTask();

            for (int i = 0; i < 5; i++) {
                task.recordFailedAttempt(repeat(COLUMN_LENGTH + 1), 5);
            }

            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.FAILED);
            assertThat(task.getAttemptCount()).isEqualTo(5);
        }
    }
}
