package com.mannschaft.app.billing;

import java.util.List;
import java.util.UUID;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）: 引継フローの通知ドメインイベント。
 *
 * <p>業務トランザクション内では本イベントの {@code publishEvent} だけを行い、
 * {@code NotificationService} を直接呼ばない（通知の DB エラーが業務書き込みを巻き戻すため。
 * 番人 {@code NotificationTransactionBoundaryGuardTest} が機械的に強制する）。
 * 実配送は {@link BillingPayerHandoverNotificationListener} が {@code AFTER_COMMIT} で行う。</p>
 *
 * <p>描画済み文字列は載せず、業務上の事実だけを載せる（文面の組み立てと locale 解決は
 * 受信者ごとにリスナー側で行う・金型 {@code PaymentAdvanceSettledNotificationEvent} と同流儀）。</p>
 *
 * @param kind              通知種別
 * @param handoverRequestId {@code billing_payer_handover_requests.id}
 * @param scopeKind         TEAM / ORG（USER は引継対象外）
 * @param scopeId           teams.id / organizations.id
 * @param recipientUserIds  宛先ユーザー ID 一覧（空なら配送しない）
 * @param actorUserId       操作者（{@code null} 可）
 */
public record BillingPayerHandoverNotificationEvent(
        BillingPayerHandoverNotificationKind kind,
        UUID handoverRequestId,
        EntitlementScopeKind scopeKind,
        Long scopeId,
        List<Long> recipientUserIds,
        Long actorUserId) {
}
