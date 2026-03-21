package com.mannschaft.app.ticket.service;

import com.mannschaft.app.ticket.entity.TicketProductEntity;

/**
 * Stripe 回数券決済サービスインターフェース。
 *
 * <p>Stripe API との連携を抽象化する。テスト時はモック実装に差し替え可能。</p>
 */
public interface StripeTicketService {

    /**
     * Stripe Product + Price を作成する。
     *
     * @param product 回数券商品エンティティ
     * @return Stripe の Product ID と Price ID を格納した結果
     */
    StripeProductResult createStripeProduct(TicketProductEntity product);

    /**
     * Stripe Price を再作成する（価格変更時）。
     *
     * @param product         回数券商品エンティティ
     * @param oldStripePriceId 旧 Stripe Price ID（active=false に更新）
     * @return 新しい Stripe Price ID
     */
    String recreateStripePrice(TicketProductEntity product, String oldStripePriceId);

    /**
     * Stripe Product を非アクティブにする（商品削除時）。
     *
     * @param stripeProductId Stripe Product ID
     */
    void deactivateStripeProduct(String stripeProductId);

    /**
     * Stripe Checkout Session を作成する。
     *
     * @param product    回数券商品エンティティ
     * @param userId     購入者のユーザーID
     * @param paymentId  ticket_payments の ID（metadata 用）
     * @param bookId     ticket_books の ID（metadata 用）
     * @param successUrl 決済成功時のリダイレクト URL
     * @param cancelUrl  決済キャンセル時のリダイレクト URL
     * @return Checkout Session の結果
     */
    CheckoutSessionResult createCheckoutSession(TicketProductEntity product, Long userId,
                                                 Long paymentId, Long bookId,
                                                 String successUrl, String cancelUrl);

    /**
     * Stripe Refund を作成する。
     *
     * @param paymentIntentId Stripe PaymentIntent ID
     * @param amount          返金額（円）
     * @return Stripe Refund ID
     */
    String createRefund(String paymentIntentId, int amount);

    /**
     * Stripe Product + Price 作成結果。
     */
    record StripeProductResult(String productId, String priceId) {}

    /**
     * Stripe Checkout Session 作成結果。
     */
    record CheckoutSessionResult(String checkoutUrl, String sessionId, java.time.LocalDateTime expiresAt) {}
}
