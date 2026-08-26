package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * F08.9 P1 Wave5: 会費 Connect 即時チェックアウトリクエスト（設計書 F08.9 02 §1.1）。
 *
 * <p>払い手は常に {@code SecurityUtils.getCurrentUserId()}（ログインユーザー本人）で解決する。
 * 受益者（beneficiaryUserId）は必須パラメータ。本人払いの場合は {@code beneficiaryUserId == payerUserId} となり、
 * Service 層の {@link com.mannschaft.app.payment.service.PaymentAuthorizationService#authorizePayment}
 * が SELF 権原として処理する。</p>
 *
 * <p>{@code idempotencyKey} は省略可（省略時は Controller で UUID を生成して補完する）。
 * Stripe の idempotency_key へ橋渡しされる（設計書 §0 冪等性）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MembershipCheckoutRequest {

    /**
     * 受益者ユーザーID（会費の支払い対象者）。必須。
     * 本人払いの場合は払い手と同じユーザーIDを指定する。
     */
    @NotNull
    private Long beneficiaryUserId;

    /**
     * 冪等性キー（省略時は Controller で UUID 生成）。
     * Idempotency-Key ヘッダと統合し Stripe へ橋渡しする。
     */
    private String idempotencyKey;
}
