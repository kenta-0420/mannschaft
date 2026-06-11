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
 *   <li>{@code subKey} — 手数料パターン解決の細分キー（R1・助っ人＝{@code recruitment_category} 値 等）。
 *       {@link com.mannschaft.app.payment.FeePolicyResolver} に渡す。{@code null}＝source_kind の既定割当を引く
 *       （設計書 02 §3.5.1）。</li>
 *   <li>{@code deferred} — 第三陣-b「7日超 fallback」フラグ（マスター裁可）。{@code true}＝成立〜役務日が7日超で
 *       カード与信が役務完了前に失効するため、成立時に与信（manual-capture PI）を立てず
 *       {@link EscrowStatus#DEFERRED}（PI 未作成・完了時即時払い予定）で起票する。最終認証時に即時払い
 *       （{@link EscrowCaptureMode#AUTOMATIC} の destination charge）へフォールバックする。{@code false}（既定）＝
 *       7日以内 or 役務日不明（安全側）で従来どおり与信（MANUAL）を立てる（設計書 02 §5.1）。</li>
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
        Long actorUserId,
        String subKey,
        boolean deferred) {

    /**
     * 後方互換コンストラクタ（{@code subKey=null}＝source_kind の既定手数料パターン・{@code deferred=false}＝従来与信）。
     * 既存のイベント駆動経路・テストはこちらを用い、R1 で手数料パターンの細分（sub_key）を渡す場合のみ
     * 全引数コンストラクタを使う。
     */
    public AuthorizeChargeCommand(
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
        this(sourceKind, sourceId, sourceParticipantId, payerScopeKind, payerScopeId, payerStripeCustomerId,
                payeeKind, payeeScopeId, faceAmount, currency, organizationId, actorUserId, null, false);
    }

    /**
     * 後方互換コンストラクタ（{@code subKey} 指定あり・{@code deferred=false}＝従来与信）。
     * 手数料細分キーを渡しつつ従来の与信（7日以内 or 役務日不明）を行う経路向け。
     */
    public AuthorizeChargeCommand(
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
            Long actorUserId,
            String subKey) {
        this(sourceKind, sourceId, sourceParticipantId, payerScopeKind, payerScopeId, payerStripeCustomerId,
                payeeKind, payeeScopeId, faceAmount, currency, organizationId, actorUserId, subKey, false);
    }
}
