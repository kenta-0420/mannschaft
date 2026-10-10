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
 * @param actorId         通知の実行者ID（管理者の手動リマインド {@code MemberInfoResponseService#sendRemind} が
 *                        依頼者を載せる。定期バッチは {@code null}＝システムトリガー）
 */
public record MemberInfoUpdateReminderNotificationEvent(
        Long teamId,
        Long recipientUserId,
        Long fieldId,
        Long actorId) {

    /** 定期バッチ用（実行者なし＝システムトリガー）。 */
    public MemberInfoUpdateReminderNotificationEvent(Long teamId, Long recipientUserId, Long fieldId) {
        this(teamId, recipientUserId, fieldId, null);
    }
}
