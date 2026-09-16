package com.mannschaft.app.contact.event;

/**
 * 招待リンク使用の通知発火イベント（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code ContactInviteTokenService#acceptInvite} は業務トランザクションの内側で本イベントを
 * publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、通知の文面組み立て
 * （アクター名解決・ロケール解決・件名/本文組み立て）は {@link ContactInviteUsedNotificationListener}
 * （{@code AFTER_COMMIT}）側で行う（{@link ContactRequestNotificationEvent} と同型）。</p>
 *
 * @param actorId  招待リンクを使用したユーザーID（通知本文に載せるアクター）
 * @param issuerId 招待リンク発行者のユーザーID（通知の宛先）
 * @param tokenId  招待トークンID（{@code sourceId} に使う）
 */
public record ContactInviteUsedNotificationEvent(Long actorId, Long issuerId, Long tokenId) {
}
