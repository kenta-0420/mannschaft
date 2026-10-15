package com.mannschaft.app.ticket.service;

import com.mannschaft.app.ticket.entity.TicketProductEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Stripe 回数券決済サービスのプレースホルダー実装。
 *
 * <p>Stripe SDK 導入前の仮実装。全メソッドでダミー値を返す。
 * 本番環境では Stripe SDK を使った実装に差し替える。</p>
 */
@Slf4j
@Service
public class StripeTicketServicePlaceholder implements StripeTicketService {

    @Override
    public StripeProductResult createStripeProduct(TicketProductEntity product) {
        log.info("【Stripe Placeholder】Product + Price 作成: name={}, price={}", product.getName(), product.getPrice());
        return new StripeProductResult("prod_placeholder_" + product.getId(), "price_placeholder_" + product.getId());
    }

    @Override
    public String recreateStripePrice(TicketProductEntity product, String oldStripePriceId) {
        log.info("【Stripe Placeholder】Price 再作成: name={}, oldPriceId={}", product.getName(), oldStripePriceId);
        return "price_placeholder_new_" + product.getId();
    }

    @Override
    public void deactivateStripeProduct(String stripeProductId) {
        log.info("【Stripe Placeholder】Product 非アクティブ化: productId={}", stripeProductId);
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(TicketProductEntity product, Long userId,
                                                        Long paymentId, Long bookId,
                                                        String successUrl, String cancelUrl) {
        log.info("【Stripe Placeholder】Checkout Session 作成: product={}, userId={}", product.getName(), userId);
        String sessionId = "cs_placeholder_" + paymentId;
        String checkoutUrl = "https://checkout.stripe.com/c/pay/" + sessionId;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        return new CheckoutSessionResult(checkoutUrl, sessionId, expiresAt);
    }

    @Override
    public String createRefund(String paymentIntentId, int amount) {
        log.info("【Stripe Placeholder】Refund 作成: paymentIntentId={}, amount={}", paymentIntentId, amount);
        return "re_placeholder_" + paymentIntentId;
    }
}
