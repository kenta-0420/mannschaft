package com.mannschaft.app.payment.escrow.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * F22.1 謝礼決済 P2-c 第二波: 返金リクエスト（設計書 02 §6.1・設定A）。
 *
 * <p>{@code amount} は<b>額面ベース</b>の返金額（{@code 0 < amount ≤ face_amount − 既返金累計}）。
 * {@code null} の場合は残額全額を返金（capture 前は与信取消）する。capture 後は Stripe Refund
 * （{@code reverse_transfer:true}/{@code refund_application_fee:false}）、capture 前は与信取消（課金なし）。</p>
 *
 * @param amount       返金額（額面ベース・最小通貨単位・{@code null}=全額）
 * @param reason       返金理由（{@code requested_by_customer}/{@code duplicate}/{@code cancellation} 等）
 * @param reasonDetail 補足（PII 非含意・任意）
 */
public record RefundRequest(
        @Positive Long amount,
        @Size(max = 32) String reason,
        @Size(max = 500) String reasonDetail) {
}
