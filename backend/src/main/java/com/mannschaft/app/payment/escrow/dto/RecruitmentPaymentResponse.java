package com.mannschaft.app.payment.escrow.dto;

import com.mannschaft.app.payment.escrow.EscrowStatus;

import java.util.UUID;

/**
 * F22.1 謝礼決済 第二陣: 札主の決済確認 / エスクロー状態照会レスポンス（設計書 02 §1 行#8 / 03 §1）。
 *
 * <p>札主（支払者本人）の決済確認画面が、自分の謝礼エスクローの {@code clientSecret} と手数料内訳・状態を
 * 取得するための DTO。{@code clientSecret} は<b>支払者本人のみ</b>に非 null で返し、受取側 ADMIN や無関係者には
 * 返さない（PCI SAQ-A・03 §1 / §10）。受取側 ADMIN の照会では {@code clientSecret=null} とし、状態・金額のみ返す。</p>
 *
 * <p>状態別の意味:</p>
 * <ul>
 *   <li>{@link EscrowStatus#PENDING_CONFIRMATION} — PI 作成済・札主の confirm 待ち。札主本人なら
 *       {@code clientSecret} を非 null で返す（Stripe.js で confirm する）。</li>
 *   <li>{@link EscrowStatus#AUTHORIZED} 以降 — 既に与信確定済み（確認済み）。{@code clientSecret} は不要のため
 *       null（再 confirm させない）。</li>
 *   <li>{@link EscrowStatus#HELD} — 受取側 onboarding 未完了で PI 未作成（受取口座登録待ち）。{@code clientSecret} は null。</li>
 * </ul>
 *
 * <p>金額は最小通貨単位（円整数・long）。手数料内訳（額面/課金額/Mannschaft 手数料）を同梱し、確認画面で
 * 「額面 / 支払手数料 2.5% / 合計」を表示できるようにする（04 §3.1）。{@code pi_xxx}/{@code acct_xxx} は載せない。</p>
 *
 * @param clientSecret         Stripe PaymentIntent の client_secret（支払者本人 × PENDING_CONFIRMATION 時のみ非 null・他は null）
 * @param escrowTransactionId  エスクロー取引 ID
 * @param status               エスクロー状態
 * @param faceAmount           額面（受取側が設定した謝礼の元値・円整数）
 * @param chargeAmount         課金額（支払者への実請求額＝額面 + 2.5% 上乗せ・円整数）
 * @param applicationFeeAmount Mannschaft 徴収手数料（円整数）
 */
public record RecruitmentPaymentResponse(
        String clientSecret,
        UUID escrowTransactionId,
        EscrowStatus status,
        long faceAmount,
        long chargeAmount,
        long applicationFeeAmount) {
}
