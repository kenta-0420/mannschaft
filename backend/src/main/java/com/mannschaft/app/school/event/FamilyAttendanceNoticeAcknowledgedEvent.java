package com.mannschaft.app.school.event;

/**
 * 保護者連絡の担任確認イベント（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code FamilyAttendanceNoticeService#acknowledgeNotice} は業務トランザクションの内側では
 * 本イベントを publish するだけに留め、保護者への確認通知は
 * {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * @param noticeId 確認済みになった保護者連絡のID
 */
public record FamilyAttendanceNoticeAcknowledgedEvent(Long noticeId) {
}
