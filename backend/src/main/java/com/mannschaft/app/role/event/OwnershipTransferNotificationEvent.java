package com.mannschaft.app.role.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;

/**
 * オーナー委譲オファーの付随通知（打診到達・辞退）の配送要求イベント（Issue #2997 / CMP-260827-1152 G5）。
 *
 * <p>{@code OwnershipTransferOfferService#createOffer} / {@code #declineOffer} の業務トランザクション内で
 * publish し、{@link OwnershipTransferNotificationListener} が {@code AFTER_COMMIT} で受け取って配送する。
 * 業務TXがロールバックすれば通知は作られず、通知側の失敗が打診・辞退の確定を巻き戻さない。</p>
 *
 * @param notificationType 通知種別（{@code OWNERSHIP_TRANSFER_OFFERED} / {@code OWNERSHIP_TRANSFER_DECLINED}）
 * @param recipientUserId  受信者（前段検証済みで null でない）
 * @param priority         優先度
 * @param title            件名
 * @param body             本文
 * @param scopeType        通知スコープ種別
 * @param scopeId          通知スコープID
 * @param actionUrl        遷移先
 * @param actorId          実行者ID
 */
public record OwnershipTransferNotificationEvent(
        String notificationType,
        Long recipientUserId,
        NotificationPriority priority,
        String title,
        String body,
        NotificationScopeType scopeType,
        Long scopeId,
        String actionUrl,
        Long actorId) {
}
