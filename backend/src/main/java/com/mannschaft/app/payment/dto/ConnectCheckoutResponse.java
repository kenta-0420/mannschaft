package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * F08.9 P1 Wave4: 会費の Connect 即時 charge チェックアウトレスポンス（設計書 F08.9 02 §1.1 / §9）。
 *
 * <p>既存の {@link CheckoutResponse}（F08.2 Stripe Checkout・{@code checkoutUrl}/{@code sessionId} を返す
 * リダイレクト型）とは別物。会費の新規決済は Connect Destination PaymentIntent の即時 charge へ切り替わり、
 * 払い手本人が Stripe.js で {@code clientSecret} を confirm（カード直送・PCI SAQ-A・03 §1）する。
 * 既存の素 Checkout（自社集金）経路は壊さないため、本 Connect 経路は専用 DTO を新設する。</p>
 *
 * <ul>
 *   <li>{@code clientSecret} — 払い手本人のみへ返す PaymentIntent の client secret（他人へ漏らさない）。</li>
 *   <li>{@code memberPaymentId} — PENDING で起票した {@code member_payments.id}（PAID 反映の追跡キー）。</li>
 *   <li>{@code escrowTransactionId} — 連結した {@code escrow_transactions.id}（money rail 突合キー）。</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class ConnectCheckoutResponse {

    private final String clientSecret;
    private final Long memberPaymentId;
    private final UUID escrowTransactionId;
}
