package com.mannschaft.app.tournament.fee.dto;

/**
 * 大会参加費 Connect 決済チェックアウトリクエスト（F08.7.1 Connect 決済）。
 *
 * <p>{@code idempotencyKey} は省略可。省略（null または空白）の場合、
 * {@code TournamentFeePaymentService} 内で自動生成する。</p>
 */
public record TournamentFeeCheckoutRequest(
        /** 冪等性キー（省略可・Stripe へ橋渡し）。 */
        String idempotencyKey
) {
}
