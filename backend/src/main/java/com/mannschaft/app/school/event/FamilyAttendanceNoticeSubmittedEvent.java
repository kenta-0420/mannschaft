package com.mannschaft.app.school.event;

/**
 * 保護者連絡の送信完了イベント（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code FamilyAttendanceNoticeService#submitNotice} は業務トランザクションの内側では
 * 本イベントを publish するだけに留め、担任への通知は
 * {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <p>連絡の内容（生徒ID・連絡種別・理由）は {@code family_attendance_notices} から
 * 読み直せるため積まない。とくに {@code reasonDetail} は暗号化カラムであり
 * イベントに載せて流通させるべきではない。</p>
 *
 * @param noticeId 送信された保護者連絡のID
 */
public record FamilyAttendanceNoticeSubmittedEvent(Long noticeId) {
}
