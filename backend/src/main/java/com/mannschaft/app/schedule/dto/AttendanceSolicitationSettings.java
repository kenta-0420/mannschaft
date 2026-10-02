package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.MinResponseRole;

import java.time.LocalDateTime;

/**
 * 出欠募集開始時に予定本体へ適用する出欠設定（機能55 / Issue #2508 欠陥B）。
 *
 * <p>予約出欠募集（{@code ScheduleScheduledTaskEntity.payloadJson}）に保存されたユーザー指定の
 * 設定を、materialize 時に {@code ScheduleAttendanceService.openAttendanceSolicitation} へ引き渡すための
 * 値オブジェクト。各項目は <b>null = 未指定（予定の既存値を保つ）</b> を意味する。</p>
 *
 * <p><b>回帰防止</b>: 以前はバッチが {@code payload_json} を一度も読まず、締切・コメント設定・
 * 最低応答ロールが「保存されるだけで一切適用されない」状態だった。本型はその設定を
 * 型として明示し、経路の途中で黙って捨てられないようにする。</p>
 *
 * @param attendanceDeadline 出欠回答期限（JST の {@link LocalDateTime}。null = 未指定）
 * @param commentOption      コメント要否（null = 未指定）
 * @param minResponseRole    出欠回答の最低ロール（null = 未指定）
 */
public record AttendanceSolicitationSettings(
        LocalDateTime attendanceDeadline,
        CommentOption commentOption,
        MinResponseRole minResponseRole) {

    /** 設定なし（予定の既存値をそのまま使う）。 */
    public static final AttendanceSolicitationSettings NONE =
            new AttendanceSolicitationSettings(null, null, null);

    /** いずれの項目も指定されていないか。 */
    public boolean isEmpty() {
        return attendanceDeadline == null && commentOption == null && minResponseRole == null;
    }
}
