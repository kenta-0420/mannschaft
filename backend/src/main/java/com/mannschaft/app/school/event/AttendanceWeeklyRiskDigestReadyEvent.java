package com.mannschaft.app.school.event;

/**
 * 週次リスクダイジェストの配送対象チームが確定したことを表すイベント（Issue #2990 L6 TX_NOTIFY_BARE 是正の追補）。
 *
 * <p>{@code AttendanceRequirementBatchService#sendWeeklyDigest} は
 * {@code @Transactional(readOnly = true)} のバッチトランザクション内では本イベントを publish するだけに留め、
 * 担任へのダイジェスト通知は {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <p>リスク生徒数と担任のユーザーIDは {@code teamId} と {@code academicYear} から読み直せるため積まない
 * （描画済み文字列・日時も積まない）。</p>
 *
 * @param teamId       対象チームID
 * @param academicYear 対象年度
 */
public record AttendanceWeeklyRiskDigestReadyEvent(
        Long teamId,
        Integer academicYear) {
}
