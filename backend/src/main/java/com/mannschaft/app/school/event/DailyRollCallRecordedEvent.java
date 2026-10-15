package com.mannschaft.app.school.event;

import java.util.List;

/**
 * 朝の点呼一括登録の完了イベント（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code DailyAttendanceService#submitDailyRollCall} は業務トランザクションの内側では
 * 本イベントを publish するだけに留め、保護者への出欠通知は
 * {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <h2>載せるのは ID だけ</h2>
 * <p>生徒ID・対象日・出欠ステータスはいずれも {@code daily_attendance_records} 行から
 * 読み直せる業務データであるため積まない。描画済みの文字列も積まない。
 * 日時型（{@code LocalDateTime}）を record コンポーネントに置くと
 * {@code DateTimeAndZoneGuardTest} が弾く。</p>
 *
 * @param teamId    クラスチームID
 * @param recordIds 登録・更新した日次出欠レコードのID一覧（業務TX内で確定したものだけ）
 */
public record DailyRollCallRecordedEvent(Long teamId, List<Long> recordIds) {
}
