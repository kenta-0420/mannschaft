package com.mannschaft.app.provisioning.event;

/**
 * 柱②-2 販促プロビジョニング: ADMIN 招待メール送信を要求するイベント（発行/再送で共通）。
 *
 * <p>{@code ProvisioningService} は業務トランザクションの内側で本イベントを publish するだけに留める
 * （通知のトランザクション境界番人対応。業務上の事実だけを積み、実配送は
 * {@link ProvisioningEmailEventListener}（{@code AFTER_COMMIT}）側で行う。
 * 金型: {@code AdminSuccessionForcedNotificationEvent} / {@code ContactRequestNotificationEvent}）。</p>
 *
 * <p>平文トークンはこのイベントとメール本文にのみ現れ、DB には保存しない
 * （{@code SecretTokenVault} 方式）。</p>
 *
 * @param inviteEmail      招待先メールアドレス
 * @param plaintextToken   平文トークン（Base64URL）
 * @param scopeName        対象組織/チーム名（メール本文の表示用）
 * @param issuedByUserId   発行者（SYSTEM_ADMIN）の user ID
 */
public record ProvisioningInvitationIssuedEvent(
        String inviteEmail, String plaintextToken, String scopeName, Long issuedByUserId) {
}
