package com.mannschaft.app.recruitment.event;

/**
 * F03.11 募集型予約リマインドの通知配送要求イベント（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>{@code RecruitmentReminderRunner#processOne} が 1 リマインダーぶんの
 * {@code recruitment_reminders.sent_at} を独立トランザクションで確定する直前に publish し、
 * {@code RecruitmentReminderNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>イベントには<b>読み直せる ID のみ</b>を載せる。募集タイトル等の業務本文は載せず、
 * 配送リスナーが {@code listingId} から読み直して組み立てる（確定設計の方針）。</p>
 *
 * @param reminderId       リマインダーID（相関ID・ログ用）
 * @param listingId        募集ID（タイトル・スコープの読み直しキー、かつ通知の {@code sourceId}）
 * @param recipientUserId  受信者ユーザーID（確定参加者）
 */
public record RecruitmentReminderNotificationEvent(
        Long reminderId,
        Long listingId,
        Long recipientUserId) {
}
