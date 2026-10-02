package com.mannschaft.app.role.event;

/**
 * 柱①「ADMINゼロ根治」— 強制承継（purge経路 / 夜次バッチ経路）で ADMIN 昇格が完了した際の
 * 通知発火イベント（通知のトランザクション境界番人 Issue #2834 / CMP-056 / #2990 対応）。
 *
 * <p>{@code RoleSuccessionService#forceTransferForPurge} / {@code #promoteForBatchSuccession} は
 * 業務トランザクションの内側で本イベントを publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、
 * 通知の文面組み立て・実配送は {@link AdminSuccessionNotificationListener}（{@code AFTER_COMMIT}）側で行う
 * （金型: {@code com.mannschaft.app.contact.event.ContactRequestNotificationEvent}）。</p>
 *
 * @param scopeType         TEAM / ORGANIZATION
 * @param scopeId           対象スコープ ID
 * @param candidateId       昇格したユーザーID（通知の宛先）
 * @param withdrawingUserId 退会（purge）対象だった旧 ADMIN ユーザーID（{@code actorId} に使う）。
 *                          {@link Reason#BATCH_ADMINLESS_SCOPE} の場合は該当者がいないため {@code null}
 * @param reason            強制承継の発生経路（通知文面の出し分けに使う）
 */
public record AdminSuccessionForcedNotificationEvent(
        String scopeType, Long scopeId, Long candidateId, Long withdrawingUserId, Reason reason) {

    /** 強制承継の発生経路。 */
    public enum Reason {
        /** {@code AccountPurgedEvent} 経由（退会ユーザーが唯一のADMINだったケース）。 */
        PURGE,
        /** {@code AdminlessScopeSuccessionBatchService} の夜次バッチ経由（既存データの検出是正）。 */
        BATCH_ADMINLESS_SCOPE
    }
}
