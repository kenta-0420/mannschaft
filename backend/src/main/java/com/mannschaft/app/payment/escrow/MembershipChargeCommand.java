package com.mannschaft.app.payment.escrow;

import java.util.UUID;

/**
 * F08.9 P1 Wave0: 会費（{@link EscrowSourceKind#MEMBERSHIP}）の即時 charge コマンド（設計書 F08.9 02 §1.1 / README §3.4）。
 *
 * <p>{@link ConnectChargeService#charge(MembershipChargeCommand)} の入力。会費は<b>即時モード</b>
 * （{@link EscrowCaptureMode#AUTOMATIC}・与信フェーズを経ない）であり、謝礼（RECRUITMENT・エスクローモード）の
 * {@link AuthorizeChargeCommand} とは別物。応募の概念（{@code sourceParticipantId} 等）は持たせない。</p>
 *
 * <p>手数料（charge / application_fee）は受け取らず、サービス内で {@code faceAmount} から
 * {@link com.mannschaft.app.payment.PaymentFeeCalculator} が一元算出する（数式の散在禁止・F22.1 02 §3.5）。</p>
 *
 * <ul>
 *   <li>{@code faceAmount} — 額面（会費額・円整数・最小通貨単位・正値）。</li>
 *   <li>{@code payeeConnectAccountId} — 受領者（チーム/組織）の {@code connect_accounts.id}（UUID）。
 *       呼び出し側（受益者→scope→Connect 口座）で既に解決済みの口座を直接指定する
 *       （謝礼の authorize が scope から解決するのと異なり、会費は払い手 API が口座 ID を渡す）。</li>
 *   <li>{@code payerStripeCustomerId} — 払い手の Stripe Customer（{@code cus_xxx}）。Destination PI の Customer。</li>
 *   <li>{@code payerUserId} — 払い手のユーザー ID。{@code escrow_transactions.payer_scope_id}（NOT NULL）へ格納し、
 *       払い手主体は常に {@link com.mannschaft.app.payment.connect.ScopeKind#USER}。F08.9 02 §1.1 の
 *       「払い手は常に {@code SecurityUtils.getCurrentUserId()}」に対応する（IDOR 監査の基点）。</li>
 *   <li>{@code sourceId} — 出所 ID（会費項目＝{@code payment_items.id}）。{@code escrow_transactions.source_id}
 *       （NOT NULL）へ格納し、{@code idempotencyKey} と併せ会費×項目の二重起票を 1 件に収束させる。</li>
 *   <li>{@code organizationId} — テナント列（受領が ORG/TEAM 配下のとき非 null・将来シャーディングのルーティングキー）。</li>
 *   <li>{@code idempotencyKey} — <b>business 冪等の必須キー</b>（Idempotency-Key ヘッダー起源・F08.9 02 §1.1）。
 *       {@code null} または blank の場合、{@link ConnectChargeService#charge} 冒頭で
 *       {@link IllegalArgumentException} を投げ escrow 二重起票を未然に防ぐ（🟡1 契約ガード・F08.9 R2-2 検分 2026-06-08）。
 *       呼び出し側（P1/P7）は <b>必ず一意値</b>（HTTP {@code Idempotency-Key} ヘッダー等）を渡すこと。
 *       Stripe へも橋渡しし、再送時の二重 PaymentIntent 作成を Stripe 側でも拒否する。</li>
 *   <li>{@code subKey} — 手数料パターン解決の細分キー（R1）。{@code null}＝source_kind（MEMBERSHIP）の既定割当を引く
 *       （{@link com.mannschaft.app.payment.FeePolicyResolver}・設計書 02 §3.5.1）。</li>
 *   <li>{@code paymentMethodId} — off-session 即時確定に用いる保存済み既定 PaymentMethod（{@code pm_xxx}・nullable）。
 *       P5 継続課金の初回 charge は払い手不在（off-session）で確定するためここに既定 PM を渡す（R2-1 根治）。
 *       {@code null}＝P1（FE on-session confirm 前提）/P7（同様）の従来挙動（PI を未 confirm で作成）。</li>
 *   <li>{@code confirmImmediately} — {@code true} のとき {@code paymentMethodId} を添付して
 *       {@code setConfirm(true)+setOffSession(true)} で<b>server-side 即時確定</b>する（R2-1）。
 *       {@code false}（既定・後方互換）＝従来どおり未 confirm の PI を作成し FE の on-session confirm に委ねる。</li>
 * </ul>
 */
public record MembershipChargeCommand(
        long faceAmount,
        UUID payeeConnectAccountId,
        String payerStripeCustomerId,
        Long payerUserId,
        Long sourceId,
        Long organizationId,
        String idempotencyKey,
        String subKey,
        String paymentMethodId,
        boolean confirmImmediately) {

    /**
     * 後方互換コンストラクタ（{@code subKey=null}＝MEMBERSHIP の既定手数料パターンを引く・
     * {@code paymentMethodId=null}/{@code confirmImmediately=false}＝従来の未 confirm PI 作成）。
     * 既存の会費 charge 経路（P1）・協会請求（P7）・既存テストはこちらを用いる。
     */
    public MembershipChargeCommand(
            long faceAmount,
            UUID payeeConnectAccountId,
            String payerStripeCustomerId,
            Long payerUserId,
            Long sourceId,
            Long organizationId,
            String idempotencyKey) {
        this(faceAmount, payeeConnectAccountId, payerStripeCustomerId, payerUserId, sourceId, organizationId,
                idempotencyKey, null, null, false);
    }

    /**
     * 後方互換コンストラクタ（{@code subKey} 指定あり・{@code paymentMethodId=null}/{@code confirmImmediately=false}）。
     * 手数料細分キーを渡しつつ従来の未 confirm PI 作成を行う既存経路向け。
     */
    public MembershipChargeCommand(
            long faceAmount,
            UUID payeeConnectAccountId,
            String payerStripeCustomerId,
            Long payerUserId,
            Long sourceId,
            Long organizationId,
            String idempotencyKey,
            String subKey) {
        this(faceAmount, payeeConnectAccountId, payerStripeCustomerId, payerUserId, sourceId, organizationId,
                idempotencyKey, subKey, null, false);
    }
}
