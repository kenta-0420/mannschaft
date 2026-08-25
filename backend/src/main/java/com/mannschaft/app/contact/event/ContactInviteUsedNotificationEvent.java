package com.mannschaft.app.contact.event;

/**
 * 連絡先招待リンク使用時に発火する通知イベント（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code ContactInviteTokenService#acceptInvite} は業務トランザクションの内側で本イベントを
 * publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、通知の文面組み立て
 * （アクター名解決・ロケール解決・件名/本文組み立て）は行わない
 * （{@code ContactInviteUsedNotificationListener} が {@code AFTER_COMMIT} で行う）。</p>
 *
 * @param actorId  招待リンクを使用したユーザーID（通知本文に載せるアクター）
 * @param issuerId 招待リンクの発行者ユーザーID（通知の宛先）
 * @param tokenId  招待トークンID（{@code sourceId} に使う）
 */
public record ContactInviteUsedNotificationEvent(Long actorId, Long issuerId, Long tokenId) {
}
