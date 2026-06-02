package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 予約出欠募集リクエスト（機能55 第二陣）。
 *
 * <p>予定作成時に「この時刻になったら出欠募集を開始する（出欠レコードを生成し対象メンバーへ募集通知を配信する）」
 * 予約を表す。{@link #scheduledAt} 到来時に後続バッチ（{@code ScheduleScheduledTaskBatchService}）が
 * {@code ScheduleAttendanceService.openAttendanceSolicitation(scheduleId)} を呼び出す。</p>
 *
 * <p>出欠の各種設定（締切・コメント要否・最低応答ロール）は予定本体（{@code schedules}）の出欠属性に準ずる。
 * 予約タスクには materialize に必要な最小スナップショットのみを保持する。</p>
 */
@Getter
@RequiredArgsConstructor
public class ScheduledAttendanceRequest {

    /** この時刻に出欠募集を開始する。 */
    @NotNull
    @Future
    private final LocalDateTime scheduledAt;

    /** 出欠回答期限（任意）。予定本体の出欠期限に準ずる。 */
    private final LocalDateTime attendanceDeadline;

    /** コメント要否（OPTIONAL / REQUIRED / DISABLED 等。任意。{@code schedules.comment_option} に準ずる）。 */
    private final String commentOption;

    /** 出欠回答の最低ロール（任意。{@code schedules.min_response_role} に準ずる）。 */
    private final String minResponseRole;
}
