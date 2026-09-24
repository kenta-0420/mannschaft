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
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MembershipCheckoutRequest {

    /**
     * 旧ボディ形式との一時的なソース互換用。冪等キーは HTTP ヘッダだけを正とする。
     */
    public MembershipCheckoutRequest(Long beneficiaryUserId, String ignoredIdempotencyKey) {
        this.beneficiaryUserId = beneficiaryUserId;
    }

    /**
     * 受益者ユーザーID（会費の支払い対象者）。必須。
     * 本人払いの場合は払い手と同じユーザーIDを指定する。
     */
    @NotNull
    private Long beneficiaryUserId;

}
