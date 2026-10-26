package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F08.9 P5 第二波: 継続課金 加入（subscribe）リクエスト（設計書 02 §4.1）。
 *
 * <p>払い手は常に {@code SecurityUtils.getCurrentUserId()}（ログインユーザー本人）で解決する。
 * SetupIntent での PM 保存は事前に {@code POST /api/v1/me/payment-methods/setup-intent} → confirm 済みであり、
 * 本リクエストでは {@code beneficiaryUserId}（必須）と {@code billingAnchorDay}（任意・記録のみ）を受け取る。
 * 設計書 §4.1 の {@code paymentMethodSetup: <SetupIntent結果>} は「事前に PM を Customer 既定へ保存しておく」
 * という意味であり、本実装では保存済み default PM を Service 層が参照する（未保存なら 409）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeRequest {

    /** 受益者ユーザーID（会費の支払い対象者）。必須。本人加入なら払い手と同一。 */
    @NotNull
    private Long beneficiaryUserId;

    /** ユーザ指定決済日（1-28・任意・記録のみ・本波の anchor 算出は次サイクル開始）。 */
    @Min(1)
    @Max(28)
    private Short billingAnchorDay;

    /** 冪等性キー（省略時は Controller で UUID 生成）。Idempotency-Key ヘッダと統合し Stripe へ橋渡しする。 */
    private String idempotencyKey;
}
