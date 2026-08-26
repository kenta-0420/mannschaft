package com.mannschaft.app.memberinfo.event;

/**
 * F14.2 メンバー情報更新リマインドの通知配送要求イベント（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>{@code MemberInfoUpdateReminderRunner#markReminderSent} が 1 メンバーぶんの
 * {@code team_member_info_responses.last_reminder_sent_at} を独立トランザクションで確定する直前に
 * publish し、{@code MemberInfoUpdateReminderNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>イベントには<b>読み直せる ID のみ</b>を載せる。通知本文に埋め込むフィールド名（利用者が定義した
 * 業務データ）は載せず、配送リスナーが {@code fieldId} から読み直して組み立てる（確定設計の方針）。</p>
 *
 * @param teamId          チームID（通知スコープおよびアクションURL）
 * @param recipientUserId 受信者ユーザーID
 * @param fieldId         通知本文に名称を埋めるフィールドのID（期限切れ・未回答の先頭フィールド）
 */
public record MemberInfoUpdateReminderNotificationEvent(
        Long teamId,
        Long recipientUserId,
        Long fieldId) {
}
