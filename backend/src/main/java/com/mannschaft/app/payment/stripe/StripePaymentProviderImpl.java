package com.mannschaft.app.payment.stripe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stripe 決済プロバイダー実装（プレースホルダー）。
 * <p>
 * 実際の Stripe SDK 統合は Phase 8 本番実装時に行う。
 * テスト・開発用にダミー値を返す。
 */
@Slf4j
@Service
public class StripePaymentProviderImpl implements StripePaymentProvider {

    @Override
    public String createProduct(String name, Long paymentItemId) {
        log.info("Stripe Product 作成（プレースホルダー）: name={}, paymentItemId={}", name, paymentItemId);
        return "prod_placeholder_" + paymentItemId;
    }

    @Override
    public String createPrice(String stripeProductId, BigDecimal amount, String currency) {
        log.info("Stripe Price 作成（プレースホルダー）: productId={}, amount={}, currency={}",
                stripeProductId, amount, currency);
        return "price_placeholder_" + System.currentTimeMillis();
    }

    @Override
    public void archivePrice(String stripePriceId) {
        log.info("Stripe Price アーカイブ（プレースホルダー）: priceId={}", stripePriceId);
    }

    @Override
    public void archiveProduct(String stripeProductId) {
        log.info("Stripe Product アーカイブ（プレースホルダー）: productId={}", stripeProductId);
    }

    @Override
    public PriceInfo retrievePrice(String stripePriceId) {
        log.info("Stripe Price 取得（プレースホルダー）: priceId={}", stripePriceId);
        return new PriceInfo(stripePriceId, "prod_placeholder", BigDecimal.ZERO, "JPY");
    }

    @Override
    public String createCustomer(String email, Long userId) {
        log.info("Stripe Customer 作成（プレースホルダー）: email={}, userId={}", email, userId);
        return "cus_placeholder_" + userId;
    }

    @Override
    public CheckoutSessionInfo createCheckoutSession(String stripePriceId, String stripeCustomerId,
                                                     Long memberPaymentId, String successUrl, String cancelUrl) {
        log.info("Stripe Checkout Session 作成（プレースホルダー）: priceId={}, customerId={}, paymentId={}",
                stripePriceId, stripeCustomerId, memberPaymentId);
        String sessionId = "cs_placeholder_" + memberPaymentId;
        return new CheckoutSessionInfo(
                sessionId,
                "https://checkout.stripe.com/pay/" + sessionId,
                LocalDateTime.now().plusMinutes(30)
        );
    }

    @Override
    public String createRefund(String stripePaymentIntentId, Long memberPaymentId, Long refundedBy) {
        log.info("Stripe Refund 作成（プレースホルダー）: paymentIntentId={}, paymentId={}, refundedBy={}",
                stripePaymentIntentId, memberPaymentId, refundedBy);
        return "re_placeholder_" + memberPaymentId;
    }

    @Override
    public SessionStatusInfo retrieveSessionStatus(String stripeCheckoutSessionId) {
        log.info("Stripe Session 状態取得（プレースホルダー）: sessionId={}", stripeCheckoutSessionId);
        return new SessionStatusInfo("complete", "pi_placeholder", "succeeded");
    }

    @Override
    public WebhookEventInfo constructEvent(String payload, String sigHeader) {
        log.info("Stripe Webhook イベント構築（プレースホルダー）");
        return new WebhookEventInfo(
                "checkout.session.completed", null, null, null, null,
                BigDecimal.ZERO, null, null, null, null
        );
    }
}
