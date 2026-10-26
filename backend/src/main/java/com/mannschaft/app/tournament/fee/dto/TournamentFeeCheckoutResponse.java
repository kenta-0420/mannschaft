package com.mannschaft.app.tournament.fee.dto;

import java.util.UUID;

/**
 * 大会参加費 Connect 決済チェックアウトレスポンス（F08.7.1 Connect 決済）。
 *
 * <p>{@link com.mannschaft.app.payment.dto.ConnectCheckoutResponse} と同じ3フィールドを
 * tournament-fee ドメイン用に再定義。payment ドメインの DTO への直接依存を避けるため分離する。</p>
 */
public record TournamentFeeCheckoutResponse(
        /** Stripe PaymentIntent の clientSecret（払い手本人にのみ返す）。 */
        String clientSecret,
        /** PENDING で起票した member_payments.id。 */
        Long memberPaymentId,
        /** 連結した escrow_transactions.id。 */
        UUID escrowTransactionId
) {
}
