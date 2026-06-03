package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;

/**
 * F22.1 統一決済 P2-b: 謝礼の与信（authorize）コマンド（設計書 02 §0 / §5.1）。
 *
 * <p>{@link ConnectChargeService#authorize} の入力。手数料（charge / application_fee）は
 * 受け取らず、サービス内で {@code faceAmount} から {@link com.mannschaft.app.payment.PaymentFeeCalculator}
 * が一元算出する（数式の散在禁止・設計書 02 §3.5）。</p>
 *
 * <ul>
 *   <li>{@code sourceKind}/{@code sourceId}/{@code sourceParticipantId} — 出所（札 × 応募）。冪等キーの構成要素。</li>
 *   <li>{@code payerScopeKind}/{@code payerScopeId} — 支払者の主体（USER 等）。</li>
 *   <li>{@code payerStripeCustomerId} — 支払者の Stripe Customer（{@code cus_xxx}）。</li>
 *   <li>{@code payeeKind}/{@code payeeScopeId} — 受領主体（USER/TEAM/ORG × scope_id）。Connect 口座解決に使う。</li>
 *   <li>{@code faceAmount} — 額面（円整数・最小通貨単位・正値）。</li>
 *   <li>{@code currency} — 通貨（ISO 4217・{@code "JPY"} 既定）。</li>
 *   <li>{@code organizationId} — テナント列（ORG 時のみ非 null・将来シャーディングのルーティングキー）。</li>
 *   <li>{@code actorUserId} — 与信開始の操作者。<b>非 null（明示 API 経路）の場合のみ</b>札主 scope ADMIN を
 *       検証する（IDOR 防止・設計書 03 §3/§4）。{@code null}（応募成立イベント駆動の system 経路・
 *       設計書 02 §1 行#4「外部API無し」）の場合は認可済みフロー前提でスキップする。</li>
 * </ul>
 */
public record AuthorizeChargeCommand(
        EscrowSourceKind sourceKind,
        Long sourceId,
        Long sourceParticipantId,
        ScopeKind payerScopeKind,
        Long payerScopeId,
        String payerStripeCustomerId,
        ScopeKind payeeKind,
        Long payeeScopeId,
        long faceAmount,
        String currency,
        Long organizationId,
        Long actorUserId) {
}
