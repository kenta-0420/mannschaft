package com.mannschaft.app.payment.stripe;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Stripe 決済プロバイダー実装。Stripe Java SDK を使用した本番実装。
 */
@Slf4j
@Service
public class StripePaymentProviderImpl implements StripePaymentProvider {

    @Value("${mannschaft.stripe.webhook-secret:}")
    private String webhookSecret;

    @Override
    public String createProduct(String name, Long paymentItemId) {
        try {
            ProductCreateParams params = ProductCreateParams.builder()
                    .setName(name)
                    .putMetadata("paymentItemId", paymentItemId.toString())
                    .build();
            Product product = Product.create(params);
            log.info("Stripe Product 作成: id={}, name={}, paymentItemId={}", product.getId(), name, paymentItemId);
            return product.getId();
        } catch (StripeException e) {
            log.error("Stripe Product 作成失敗: name={}, paymentItemId={}", name, paymentItemId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public String createPrice(String stripeProductId, BigDecimal amount, String currency) {
        try {
            // JPY は最小通貨単位がそのまま円なので乗算不要。他の通貨は100倍する
            long unitAmount = isZeroDecimalCurrency(currency)
                    ? amount.longValue()
                    : amount.multiply(BigDecimal.valueOf(100)).longValue();

            PriceCreateParams params = PriceCreateParams.builder()
                    .setProduct(stripeProductId)
                    .setUnitAmount(unitAmount)
                    .setCurrency(currency.toLowerCase())
                    .build();
            Price price = Price.create(params);
            log.info("Stripe Price 作成: id={}, productId={}, amount={}, currency={}",
                    price.getId(), stripeProductId, amount, currency);
            return price.getId();
        } catch (StripeException e) {
            log.error("Stripe Price 作成失敗: productId={}, amount={}, currency={}",
                    stripeProductId, amount, currency, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public void archivePrice(String stripePriceId) {
        try {
            Price price = Price.retrieve(stripePriceId);
            PriceUpdateParams params = PriceUpdateParams.builder()
                    .setActive(false)
                    .build();
            price.update(params);
            log.info("Stripe Price アーカイブ: priceId={}", stripePriceId);
        } catch (StripeException e) {
            log.error("Stripe Price アーカイブ失敗: priceId={}", stripePriceId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public void archiveProduct(String stripeProductId) {
        try {
            Product product = Product.retrieve(stripeProductId);
            ProductUpdateParams params = ProductUpdateParams.builder()
                    .setActive(false)
                    .build();
            product.update(params);
            log.info("Stripe Product アーカイブ: productId={}", stripeProductId);
        } catch (StripeException e) {
            log.error("Stripe Product アーカイブ失敗: productId={}", stripeProductId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public PriceInfo retrievePrice(String stripePriceId) {
        try {
            Price price = Price.retrieve(stripePriceId);
            String currency = price.getCurrency().toUpperCase();
            BigDecimal unitAmount = isZeroDecimalCurrency(currency)
                    ? BigDecimal.valueOf(price.getUnitAmount())
                    : BigDecimal.valueOf(price.getUnitAmount()).divide(BigDecimal.valueOf(100));
            log.info("Stripe Price 取得: priceId={}, productId={}, amount={}, currency={}",
                    stripePriceId, price.getProduct(), unitAmount, currency);
            return new PriceInfo(stripePriceId, price.getProduct(), unitAmount, currency);
        } catch (StripeException e) {
            log.error("Stripe Price 取得失敗: priceId={}", stripePriceId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public String createCustomer(String email, Long userId) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .putMetadata("userId", userId.toString())
                    .build();
            Customer customer = Customer.create(params);
            log.info("Stripe Customer 作成: id={}, email={}, userId={}", customer.getId(), email, userId);
            return customer.getId();
        } catch (StripeException e) {
            log.error("Stripe Customer 作成失敗: email={}, userId={}", email, userId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public CheckoutSessionInfo createCheckoutSession(String stripePriceId, String stripeCustomerId,
                                                     Long memberPaymentId, String successUrl, String cancelUrl) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setCustomer(stripeCustomerId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(stripePriceId)
                            .setQuantity(1L)
                            .build())
                    .putMetadata("memberPaymentId", memberPaymentId.toString())
                    .build();
            Session session = Session.create(params);

            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(session.getExpiresAt()),
                    ZoneId.systemDefault());

            log.info("Stripe Checkout Session 作成: sessionId={}, memberPaymentId={}",
                    session.getId(), memberPaymentId);
            return new CheckoutSessionInfo(session.getId(), session.getUrl(), expiresAt);
        } catch (StripeException e) {
            log.error("Stripe Checkout Session 作成失敗: priceId={}, memberPaymentId={}",
                    stripePriceId, memberPaymentId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public String createRefund(String stripePaymentIntentId, Long memberPaymentId, Long refundedBy) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(stripePaymentIntentId)
                    .putMetadata("memberPaymentId", memberPaymentId.toString())
                    .putMetadata("refundedBy", refundedBy.toString())
                    .build();
            Refund refund = Refund.create(params);
            log.info("Stripe Refund 作成: refundId={}, paymentIntentId={}, memberPaymentId={}",
                    refund.getId(), stripePaymentIntentId, memberPaymentId);
            return refund.getId();
        } catch (StripeException e) {
            log.error("Stripe Refund 作成失敗: paymentIntentId={}, memberPaymentId={}",
                    stripePaymentIntentId, memberPaymentId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public SessionStatusInfo retrieveSessionStatus(String stripeCheckoutSessionId) {
        try {
            Session session = Session.retrieve(stripeCheckoutSessionId);
            String paymentIntentId = session.getPaymentIntent();
            String paymentIntentStatus = null;

            if (paymentIntentId != null) {
                com.stripe.model.PaymentIntent pi = com.stripe.model.PaymentIntent.retrieve(paymentIntentId);
                paymentIntentStatus = pi.getStatus();
            }

            log.info("Stripe Session 状態取得: sessionId={}, paymentStatus={}, piStatus={}",
                    stripeCheckoutSessionId, session.getPaymentStatus(), paymentIntentStatus);
            return new SessionStatusInfo(session.getPaymentStatus(), paymentIntentId, paymentIntentStatus);
        } catch (StripeException e) {
            log.error("Stripe Session 状態取得失敗: sessionId={}", stripeCheckoutSessionId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public WebhookEventInfo constructEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe Webhook 署名検証失敗", e);
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        String eventType = event.getType();
        log.info("Stripe Webhook イベント受信: type={}", eventType);

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);

        String sessionId = null;
        String paymentIntentId = null;
        String memberPaymentId = null;
        String subscriptionId = null;
        BigDecimal amountReceived = null;
        String receiptUrl = null;
        String refundId = null;
        BigDecimal refundAmount = null;
        BigDecimal paymentIntentAmount = null;

        if (stripeObject instanceof Session session) {
            sessionId = session.getId();
            paymentIntentId = session.getPaymentIntent();
            subscriptionId = session.getSubscription();
            Map<String, String> metadata = session.getMetadata();
            if (metadata != null) {
                memberPaymentId = metadata.get("memberPaymentId");
            }
        } else if (stripeObject instanceof com.stripe.model.PaymentIntent pi) {
            paymentIntentId = pi.getId();
            paymentIntentAmount = BigDecimal.valueOf(pi.getAmount());
            amountReceived = BigDecimal.valueOf(pi.getAmountReceived());
            Map<String, String> metadata = pi.getMetadata();
            if (metadata != null) {
                memberPaymentId = metadata.get("memberPaymentId");
            }
            // 最新 Charge から receipt_url を取得
            if (pi.getLatestCharge() != null) {
                try {
                    com.stripe.model.Charge charge = com.stripe.model.Charge.retrieve(pi.getLatestCharge());
                    receiptUrl = charge.getReceiptUrl();
                } catch (StripeException e) {
                    log.warn("Charge 取得失敗（receipt_url 省略）: chargeId={}", pi.getLatestCharge());
                }
            }
        } else if (stripeObject instanceof Refund refundObj) {
            refundId = refundObj.getId();
            paymentIntentId = refundObj.getPaymentIntent();
            refundAmount = BigDecimal.valueOf(refundObj.getAmount());
            Map<String, String> metadata = refundObj.getMetadata();
            if (metadata != null) {
                memberPaymentId = metadata.get("memberPaymentId");
            }
        }

        return new WebhookEventInfo(eventType, sessionId, paymentIntentId,
                memberPaymentId, subscriptionId, amountReceived, receiptUrl,
                refundId, refundAmount, paymentIntentAmount);
    }

    /**
     * ゼロデシマル通貨（最小単位が1の通貨）かどうかを判定する。
     * JPY, KRW など最小通貨単位に小数がない通貨が該当する。
     */
    private boolean isZeroDecimalCurrency(String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW", "VND", "BIF", "CLP", "DJF", "GNF", "ISK",
                 "KMF", "MGA", "PYG", "RWF", "UGX", "VUV", "XAF", "XOF", "XPF" -> true;
            default -> false;
        };
    }
}
