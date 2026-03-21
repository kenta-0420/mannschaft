package com.mannschaft.app.payment.stripe;

import java.math.BigDecimal;

/**
 * Stripe 決済プロバイダーインターフェース。
 * <p>
 * Stripe SDK への依存を抽象化し、テスト時にモック差し替えを可能にする。
 */
public interface StripePaymentProvider {

    /**
     * Stripe Product を作成する。
     *
     * @param name          商品名
     * @param paymentItemId 支払い項目 ID（metadata 用）
     * @return Stripe Product ID（prod_xxxxxxxxxx）
     */
    String createProduct(String name, Long paymentItemId);

    /**
     * Stripe Price を作成する。
     *
     * @param stripeProductId Stripe Product ID
     * @param amount          金額
     * @param currency        通貨コード（ISO 4217）
     * @return Stripe Price ID（price_xxxxxxxxxx）
     */
    String createPrice(String stripeProductId, BigDecimal amount, String currency);

    /**
     * Stripe Price をアーカイブ（非アクティブ化）する。
     *
     * @param stripePriceId Stripe Price ID
     */
    void archivePrice(String stripePriceId);

    /**
     * Stripe Product をアーカイブ（非アクティブ化）する。
     *
     * @param stripeProductId Stripe Product ID
     */
    void archiveProduct(String stripeProductId);

    /**
     * Stripe Price を取得し、金額と通貨を検証する。
     *
     * @param stripePriceId Stripe Price ID
     * @return Price 情報
     */
    PriceInfo retrievePrice(String stripePriceId);

    /**
     * Stripe Customer を作成する。
     *
     * @param email ユーザーのメールアドレス
     * @param userId ユーザー ID（metadata 用）
     * @return Stripe Customer ID（cus_xxxxxxxxxx）
     */
    String createCustomer(String email, Long userId);

    /**
     * Stripe Checkout Session を作成する（一回払い）。
     *
     * @param stripePriceId      Stripe Price ID
     * @param stripeCustomerId   Stripe Customer ID
     * @param memberPaymentId    支払い記録 ID（metadata 用）
     * @param successUrl         決済成功後の遷移先 URL
     * @param cancelUrl          決済キャンセル時の遷移先 URL
     * @return Checkout Session 情報
     */
    CheckoutSessionInfo createCheckoutSession(String stripePriceId, String stripeCustomerId,
                                              Long memberPaymentId, String successUrl, String cancelUrl);

    /**
     * Stripe Refund（全額返金）を実行する。
     *
     * @param stripePaymentIntentId Stripe Payment Intent ID
     * @param memberPaymentId       支払い記録 ID（metadata 用）
     * @param refundedBy            返金操作者のユーザー ID（metadata 用）
     * @return Stripe Refund ID（re_xxxxxxxxxx）
     */
    String createRefund(String stripePaymentIntentId, Long memberPaymentId, Long refundedBy);

    /**
     * Stripe Checkout Session の状態を取得する（手動再同期用）。
     *
     * @param stripeCheckoutSessionId Stripe Checkout Session ID
     * @return Session の状態情報
     */
    SessionStatusInfo retrieveSessionStatus(String stripeCheckoutSessionId);

    /**
     * Stripe Webhook の署名を検証し、イベントペイロードをパースする。
     *
     * @param payload    生リクエストボディ
     * @param sigHeader  Stripe-Signature ヘッダー
     * @return パースされたイベント情報
     */
    WebhookEventInfo constructEvent(String payload, String sigHeader);

    /**
     * Stripe Price 情報。
     */
    record PriceInfo(String priceId, String productId, BigDecimal unitAmount, String currency) {}

    /**
     * Checkout Session 情報。
     */
    record CheckoutSessionInfo(String sessionId, String checkoutUrl, java.time.LocalDateTime expiresAt) {}

    /**
     * Session 状態情報（手動再同期用）。
     */
    record SessionStatusInfo(String paymentStatus, String paymentIntentId, String paymentIntentStatus) {}

    /**
     * Webhook イベント情報。
     */
    record WebhookEventInfo(String type, String sessionId, String paymentIntentId,
                            String memberPaymentId, String subscriptionId,
                            BigDecimal amountReceived, String receiptUrl, String refundId,
                            BigDecimal refundAmount, BigDecimal paymentIntentAmount) {}
}
