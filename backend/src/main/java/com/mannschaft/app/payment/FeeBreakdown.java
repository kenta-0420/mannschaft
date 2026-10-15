package com.mannschaft.app.payment;

/**
 * F22.1 市（Market）統一決済 P2-b: 手数料折半計算の結果（額面から導出する全金額の内訳）。
 *
 * <p>全フィールドは JPY ゼロデシマル通貨を前提とした円整数（最小単位・{@code long}）。
 * 設計書: docs/features/F22.1_market/payment/02_api_design.md §3.5 / §3.5.1。</p>
 *
 * <ul>
 *   <li>{@code faceAmount} — 受取側が設定した額面（謝礼額/会費額）。計算の入力基準。</li>
 *   <li>{@code payerFee} — 支払者上乗せ手数料 = round(faceAmount × 0.025)。</li>
 *   <li>{@code chargeAmount} — 支払者への実請求額 = faceAmount + payerFee
 *       （{@code escrow_transactions.amount}・Stripe へ渡す課金額）。</li>
 *   <li>{@code applicationFeeAmount} — Mannschaft が徴収する総手数料 = round(faceAmount × 0.05)
 *       （{@code escrow_transactions.application_fee_amount}）。</li>
 *   <li>{@code transferAmount} — 受取側送金額 = chargeAmount − applicationFeeAmount（≈ 額面 − 2.5%）。</li>
 *   <li>{@code estimatedStripeFee} — 参考値: round(chargeAmount × stripeFeeRate)。実額は Stripe Webhook で記録する。</li>
 *   <li>{@code estimatedNetProfit} — 参考値: applicationFeeAmount − estimatedStripeFee（≈ 額面の 1.31%）。</li>
 * </ul>
 *
 * <p>不変条件: {@code applicationFeeAmount <= chargeAmount}（escrow_transactions の chk_et_fee と整合）。</p>
 */
public record FeeBreakdown(
        long faceAmount,
        long payerFee,
        long chargeAmount,
        long applicationFeeAmount,
        long transferAmount,
        long estimatedStripeFee,
        long estimatedNetProfit) {
}
