package com.mannschaft.app.payment.stripe;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.Transfer;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.InvoiceUpdateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stripe 決済プロバイダー実装。Stripe Java SDK を使用した本番実装。
 */
@Slf4j
@Service
public class StripePaymentProviderImpl implements StripePaymentProvider {

    @Value("${mannschaft.stripe.webhook-secret:}")
    private String webhookSecret;

    /** F22.1 Connect Webhook 用の別署名シークレット（platform 用と分離・設計書 03 §2）。 */
    @Value("${mannschaft.stripe.connect-webhook-secret:}")
    private String connectWebhookSecret;

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
    public String createRecurringPrice(String stripeProductId, BigDecimal amount, String currency,
                                       com.mannschaft.app.payment.BillingInterval billingInterval) {
        try {
            long unitAmount = isZeroDecimalCurrency(currency)
                    ? amount.longValue()
                    : amount.multiply(BigDecimal.valueOf(100)).longValue();

            PriceCreateParams.Recurring.Interval interval =
                    billingInterval == com.mannschaft.app.payment.BillingInterval.YEARLY
                            ? PriceCreateParams.Recurring.Interval.YEAR
                            : PriceCreateParams.Recurring.Interval.MONTH;

            PriceCreateParams params = PriceCreateParams.builder()
                    .setProduct(stripeProductId)
                    .setUnitAmount(unitAmount)
                    .setCurrency(currency.toLowerCase())
                    .setRecurring(PriceCreateParams.Recurring.builder()
                            .setInterval(interval)
                            .build())
                    .build();
            Price price = Price.create(params);
            log.info("Stripe 継続課金 Price 作成: id={}, productId={}, amount={}, currency={}, interval={}",
                    price.getId(), stripeProductId, amount, currency, billingInterval);
            return price.getId();
        } catch (StripeException e) {
            log.error("Stripe 継続課金 Price 作成失敗: productId={}, amount={}, currency={}, interval={}",
                    stripeProductId, amount, currency, billingInterval, e);
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
    public CheckoutSessionInfo createNotificationCreditCheckoutSession(String stripePriceId,
                                                                       String stripeCustomerId,
                                                                       Long notificationCreditPurchaseId,
                                                                       String successUrl,
                                                                       String cancelUrl) {
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
                    .putMetadata("notificationCreditPurchaseId", notificationCreditPurchaseId.toString())
                    .build();
            Session session = Session.create(params);

            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(session.getExpiresAt()),
                    ZoneId.systemDefault());

            log.info("通知クレジット Checkout Session 作成: sessionId={}, notificationCreditPurchaseId={}",
                    session.getId(), notificationCreditPurchaseId);
            return new CheckoutSessionInfo(session.getId(), session.getUrl(), expiresAt);
        } catch (StripeException e) {
            log.error("通知クレジット Checkout Session 作成失敗: priceId={}, notificationCreditPurchaseId={}",
                    stripePriceId, notificationCreditPurchaseId, e);
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
        Long notificationCreditPurchaseId = null;

        if (stripeObject instanceof Session session) {
            sessionId = session.getId();
            paymentIntentId = session.getPaymentIntent();
            subscriptionId = session.getSubscription();
            Map<String, String> metadata = session.getMetadata();
            if (metadata != null) {
                memberPaymentId = metadata.get("memberPaymentId");
                // F09.13: 通知クレジット購入のメタデータを取得
                String ncpId = metadata.get("notificationCreditPurchaseId");
                if (ncpId != null) {
                    try {
                        notificationCreditPurchaseId = Long.parseLong(ncpId);
                    } catch (NumberFormatException e) {
                        log.warn("notificationCreditPurchaseId のパース失敗: value={}", ncpId);
                    }
                }
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
                refundId, refundAmount, paymentIntentAmount, notificationCreditPurchaseId);
    }

    // ========================================
    // F22.1 謝礼決済 Connect（P2-a 実装）
    // ========================================

    @Override
    public String createConnectAccount(String country, ScopeKind scopeKind, Long scopeId) {
        try {
            AccountCreateParams params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setCountry(country)
                    .setCapabilities(AccountCreateParams.Capabilities.builder()
                            .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                    .setRequested(true)
                                    .build())
                            .build())
                    .putMetadata("scopeKind", scopeKind.name())
                    .putMetadata("scopeId", String.valueOf(scopeId))
                    .build();
            Account account = Account.create(params);
            log.info("Stripe Connect アカウント作成: id={}, scopeKind={}, scopeId={}",
                    account.getId(), scopeKind, scopeId);
            return account.getId();
        } catch (StripeException e) {
            log.error("Stripe Connect アカウント作成失敗: scopeKind={}, scopeId={}", scopeKind, scopeId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public AccountLinkInfo createAccountLink(String stripeAccountId, String returnUrl, String refreshUrl) {
        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(stripeAccountId)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setReturnUrl(returnUrl)
                    .setRefreshUrl(refreshUrl)
                    .build();
            AccountLink link = AccountLink.create(params);
            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(link.getExpiresAt()), ZoneId.systemDefault());
            log.info("Stripe AccountLink 作成: account={}", stripeAccountId);
            return new AccountLinkInfo(link.getUrl(), expiresAt);
        } catch (StripeException e) {
            log.error("Stripe AccountLink 作成失敗: account={}", stripeAccountId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public ConnectAccountInfo retrieveConnectAccount(String stripeAccountId) {
        try {
            Account account = Account.retrieve(stripeAccountId);
            return toConnectAccountInfo(account);
        } catch (StripeException e) {
            log.error("Stripe Connect アカウント取得失敗: account={}", stripeAccountId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public ConnectWebhookEventInfo constructConnectEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, connectWebhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe Connect Webhook 署名検証失敗", e);
            throw new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        String eventType = event.getType();
        boolean livemode = Boolean.TRUE.equals(event.getLivemode());
        log.info("Stripe Connect Webhook イベント受信: id={}, type={}", event.getId(), eventType);

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);

        String stripeAccountId = null;
        boolean chargesEnabled = false;
        boolean payoutsEnabled = false;
        List<String> requirementsDue = Collections.emptyList();

        if (stripeObject instanceof Account account) {
            stripeAccountId = account.getId();
            ConnectAccountInfo info = toConnectAccountInfo(account);
            chargesEnabled = info.chargesEnabled();
            payoutsEnabled = info.payoutsEnabled();
            requirementsDue = info.requirementsDue();
        } else {
            // deauthorized 等は account フィールド（event.account）が対象アカウントを示す
            stripeAccountId = event.getAccount();
        }

        return new ConnectWebhookEventInfo(event.getId(), eventType, livemode,
                stripeAccountId, chargesEnabled, payoutsEnabled, requirementsDue);
    }

    // ========================================
    // F22.1 謝礼決済 与信（P2-b 実装・Destination Charge）
    // ========================================

    @Override
    public PaymentIntentInfo createDestinationPaymentIntent(long chargeAmountMinor, String currency,
                                                            String payerCustomerId, long applicationFeeMinor,
                                                            String destinationAccountId, CaptureMethod captureMethod,
                                                            String idempotencyKey) {
        try {
            PaymentIntentCreateParams.CaptureMethod stripeCaptureMethod =
                    captureMethod == CaptureMethod.AUTOMATIC
                            ? PaymentIntentCreateParams.CaptureMethod.AUTOMATIC
                            : PaymentIntentCreateParams.CaptureMethod.MANUAL;

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(chargeAmountMinor)
                    .setCurrency(currency.toLowerCase())
                    .setCustomer(payerCustomerId)
                    .setCaptureMethod(stripeCaptureMethod)
                    .setApplicationFeeAmount(applicationFeeMinor)
                    .setOnBehalfOf(destinationAccountId)
                    .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(destinationAccountId)
                            .build())
                    .build();

            // idempotency_key で再送時の二重作成を Stripe 側でも防ぐ（設計書 02 §9）。
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            PaymentIntent intent = PaymentIntent.create(params, options);
            log.info("Stripe Destination PaymentIntent 作成: id={}, captureMethod={}, destination={}, amount={}, appFee={}",
                    intent.getId(), captureMethod, destinationAccountId, chargeAmountMinor, applicationFeeMinor);
            return new PaymentIntentInfo(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (StripeException e) {
            log.error("Stripe Destination PaymentIntent 作成失敗: destination={}, amount={}",
                    destinationAccountId, chargeAmountMinor, e);
            throw new BusinessException(ConnectPaymentErrorCode.AUTHORIZATION_FAILED, e);
        }
    }

    @Override
    public PaymentIntentInfo createAndConfirmDestinationPaymentIntent(long chargeAmountMinor, String currency,
                                                                      String payerCustomerId, long applicationFeeMinor,
                                                                      String destinationAccountId,
                                                                      CaptureMethod captureMethod,
                                                                      String paymentMethodId, String idempotencyKey) {
        try {
            PaymentIntentCreateParams.CaptureMethod stripeCaptureMethod =
                    captureMethod == CaptureMethod.AUTOMATIC
                            ? PaymentIntentCreateParams.CaptureMethod.AUTOMATIC
                            : PaymentIntentCreateParams.CaptureMethod.MANUAL;

            // 既存 createDestinationPaymentIntent と同一の destination/on_behalf_of/application_fee/capture_method に、
            // off-session 即時確定（payment_method + confirm + off_session）を加える（R2-1・設計書 02 §4.1）。
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(chargeAmountMinor)
                    .setCurrency(currency.toLowerCase())
                    .setCustomer(payerCustomerId)
                    .setCaptureMethod(stripeCaptureMethod)
                    .setApplicationFeeAmount(applicationFeeMinor)
                    .setOnBehalfOf(destinationAccountId)
                    .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(destinationAccountId)
                            .build())
                    .setPaymentMethod(paymentMethodId)
                    .setConfirm(true)
                    .setOffSession(true)
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            PaymentIntent intent = PaymentIntent.create(params, options);
            log.info("Stripe off-session 即時確定 PaymentIntent 作成: id={}, status={}, captureMethod={}, destination={}, "
                            + "amount={}, appFee={}",
                    intent.getId(), intent.getStatus(), captureMethod, destinationAccountId, chargeAmountMinor,
                    applicationFeeMinor);

            // confirm=true でも 3DS 要求（requires_action）等で succeeded にならない場合は、off-session では完結不能。
            // 孤児 PI を残さず cancel し、専用例外で症状を露出する（症状を隠さない・R2-1）。
            String status = intent.getStatus();
            if (!"succeeded".equals(status) && !"requires_capture".equals(status) && !"processing".equals(status)) {
                log.warn("off-session 確定が succeeded に至らず（status={}）。PI を cancel して専用例外で拒否: piId={}",
                        status, intent.getId());
                safeCancelPaymentIntent(intent.getId(), "offsession-cancel-" + idempotencyKey);
                throw new OffSessionConfirmationException(
                        status, "off-session confirm did not succeed: status=" + status, null);
            }
            return new PaymentIntentInfo(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (com.stripe.exception.CardException e) {
            // authentication_required / card_declined 等。Stripe が PI を生成済みなら cancel して孤児を残さない。
            String piId = (e.getStripeError() != null && e.getStripeError().getPaymentIntent() != null)
                    ? e.getStripeError().getPaymentIntent().getId() : null;
            if (piId != null) {
                safeCancelPaymentIntent(piId, "offsession-cancel-" + idempotencyKey);
            }
            log.warn("off-session 確定がカード認証要求/拒否で失敗: code={}, declineCode={}, piId={}",
                    e.getCode(), e.getDeclineCode(), piId, e);
            throw new OffSessionConfirmationException(e.getCode(), e.getMessage(), e);
        } catch (StripeException e) {
            log.error("Stripe off-session 確定 PaymentIntent 作成失敗: destination={}, amount={}",
                    destinationAccountId, chargeAmountMinor, e);
            throw new BusinessException(ConnectPaymentErrorCode.AUTHORIZATION_FAILED, e);
        }
    }

    /**
     * 確定に至らなかった PaymentIntent を cancel する（孤児を残さない・R2-1）。
     * cancel 自体の失敗は本来の確定失敗を覆い隠さないため警告ログに留め、握り潰さず元の失敗を呼び出し側へ返す。
     */
    private void safeCancelPaymentIntent(String paymentIntentId, String idempotencyKey) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
            intent.cancel(PaymentIntentCancelParams.builder().build(), options);
            log.info("off-session 確定失敗の PI を cancel（孤児防止）: piId={}", paymentIntentId);
        } catch (StripeException ce) {
            log.warn("off-session 確定失敗の PI cancel に失敗（要運用確認・本来の失敗は別途返却）: piId={}",
                    paymentIntentId, ce);
        }
    }

    @Override
    public PaymentIntentInfo captureManualPaymentIntent(String paymentIntentId, String idempotencyKey) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            // idempotency_key で再送時の二重 capture を Stripe 側でも拒否する（設計書 02 §5.3・二重防御）。
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            PaymentIntent captured = intent.capture(PaymentIntentCaptureParams.builder().build(), options);
            log.info("Stripe PaymentIntent capture 確定: id={}, status={}", captured.getId(), captured.getStatus());
            return new PaymentIntentInfo(captured.getId(), captured.getClientSecret(), captured.getStatus());
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent capture 失敗: id={}", paymentIntentId, e);
            throw new BusinessException(ConnectPaymentErrorCode.CAPTURE_FAILED, e);
        }
    }

    @Override
    public PaymentIntentInfo retrievePaymentIntentClientSecret(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            // clientSecret はログに出さない（PCI・03 §10）。status のみ記録する。
            log.info("Stripe PaymentIntent retrieve（札主の決済確認用・clientSecret 非ログ）: id={}, status={}",
                    intent.getId(), intent.getStatus());
            return new PaymentIntentInfo(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent retrieve 失敗: id={}", paymentIntentId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    @Override
    public ConnectRefundInfo createConnectRefund(String paymentIntentId, long amountMinor, String reason,
                                                 boolean reverseTransfer, boolean refundApplicationFee,
                                                 String idempotencyKey) {
        try {
            RefundCreateParams.Builder builder = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(amountMinor)
                    .setReverseTransfer(reverseTransfer)
                    .setRefundApplicationFee(refundApplicationFee);
            RefundCreateParams.Reason mappedReason = toRefundReason(reason);
            if (mappedReason != null) {
                builder.setReason(mappedReason);
            }
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            Refund refund = Refund.create(builder.build(), options);
            log.info("Stripe Connect Refund 作成: refundId={}, piId={}, amount={}, reverseTransfer={}, refundAppFee={}",
                    refund.getId(), paymentIntentId, amountMinor, reverseTransfer, refundApplicationFee);
            return new ConnectRefundInfo(refund.getId(), refund.getStatus());
        } catch (StripeException e) {
            log.error("Stripe Connect Refund 作成失敗: piId={}, amount={}", paymentIntentId, amountMinor, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    @Override
    public String resolveTransferIdFromPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            String chargeId = intent.getLatestCharge();
            if (chargeId == null) {
                log.warn("Transfer 解決不能（latest_charge なし）: piId={}", paymentIntentId);
                return null;
            }
            Charge charge = Charge.retrieve(chargeId);
            String transferId = charge.getTransfer();
            log.info("Transfer 解決: piId={}, chargeId={}, transferId={}", paymentIntentId, chargeId, transferId);
            return transferId;
        } catch (StripeException e) {
            log.error("Transfer 解決失敗: piId={}", paymentIntentId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    @Override
    public void reverseTransfer(String transferId, long amountMinor, String idempotencyKey) {
        try {
            Transfer transfer = Transfer.retrieve(transferId);
            TransferReversalCollectionCreateParams params = TransferReversalCollectionCreateParams.builder()
                    .setAmount(amountMinor)
                    // 設定A: application_fee は巻き戻さない（1.4% keep）。Stripe 既定 false だが明示しない（API 既定に従う）。
                    .build();
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            transfer.getReversals().create(params, options);
            log.info("Stripe Transfer 巻き戻し（reversal）: transferId={}, amount={}", transferId, amountMinor);
        } catch (StripeException e) {
            log.error("Stripe Transfer 巻き戻し失敗: transferId={}, amount={}", transferId, amountMinor, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    /**
     * 設計書 02 §6.1 の reason 文字列を Stripe の {@link RefundCreateParams.Reason} へ写す。
     *
     * <p>Stripe が受理する固定値は {@code duplicate}/{@code fraudulent}/{@code requested_by_customer} のみ。
     * {@code cancellation} 等の業務都合理由はこれらに含まれないため、業務理由の詳細は
     * {@code refunds.reason}/{@code reason_detail}（自社台帳）に保持し、Stripe へは
     * {@code requested_by_customer} に正規化する（マッピング不能でも握り潰さず台帳側に保持）。</p>
     */
    private RefundCreateParams.Reason toRefundReason(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "duplicate" -> RefundCreateParams.Reason.DUPLICATE;
            case "fraudulent" -> RefundCreateParams.Reason.FRAUDULENT;
            default -> RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        };
    }

    @Override
    public void cancelAuthorization(String paymentIntentId, String idempotencyKey) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            intent.cancel(PaymentIntentCancelParams.builder().build(), options);
            log.info("Stripe PaymentIntent 与信取消: id={}", paymentIntentId);
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent 与信取消失敗: id={}", paymentIntentId, e);
            throw new BusinessException(ConnectPaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    // ========================================
    // F08.9 P5 継続課金（SetupIntent 基盤＋Subscription 実装）
    // ========================================

    @Override
    public SetupIntentInfo createSetupIntent(String customerId) {
        try {
            // usage=off_session: 将来の自動課金（次サイクル以降）に再利用する PM として登録する（02 §4.1）。
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                    .setCustomer(customerId)
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                    .build();
            SetupIntent intent = SetupIntent.create(params);
            log.info("Stripe SetupIntent 作成: id={}, customer={}, status={}",
                    intent.getId(), customerId, intent.getStatus());
            return new SetupIntentInfo(intent.getId(), intent.getClientSecret(), intent.getStatus());
        } catch (StripeException e) {
            log.error("Stripe SetupIntent 作成失敗: customer={}", customerId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public void attachPaymentMethodAndSetDefault(String customerId, String paymentMethodId) {
        try {
            // (1) confirm 済み PM を Customer に attach（既に attach 済みでも冪等・Stripe が no-op 化）。
            PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
            paymentMethod.attach(PaymentMethodAttachParams.builder().setCustomer(customerId).build());

            // (2) Customer の invoice_settings.default_payment_method に設定（次サイクル invoice の off_session 既定）。
            Customer customer = Customer.retrieve(customerId);
            customer.update(CustomerUpdateParams.builder()
                    .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build())
                    .build());
            log.info("Stripe PaymentMethod attach＋default 設定: customer={}, pm={}", customerId, paymentMethodId);
        } catch (StripeException e) {
            log.error("Stripe PaymentMethod attach/default 設定失敗: customer={}, pm={}", customerId, paymentMethodId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public SubscriptionInfo createSubscription(String customerId, String priceId, String defaultPaymentMethodId,
                                               String destinationAccountId, BigDecimal applicationFeePercent,
                                               long billingCycleAnchorEpochSec, String idempotencyKey) {
        try {
            // 案b: 初回会費は外側で単発 destination charge 済み。Subscription は billing_cycle_anchor=次サイクル開始で
            // 起動し proration_behavior=NONE で「初回 invoice を発生させない」（PoC 実証 2026-06-05・02 §4.1）。
            // billing_cycle_anchor は「将来時刻」かつ proration_behavior=NONE のとき初回課金を当該時刻まで遅延でき、
            // trial_end 方式よりも「次サイクルから通常課金（subscription_cycle invoice）」を確実に表現できる。
            SubscriptionCreateParams params = SubscriptionCreateParams.builder()
                    .setCustomer(customerId)
                    .addItem(SubscriptionCreateParams.Item.builder()
                            .setPrice(priceId)
                            .setQuantity(1L)
                            .build())
                    .setDefaultPaymentMethod(defaultPaymentMethodId)
                    .setOffSession(true)
                    .setOnBehalfOf(destinationAccountId)
                    .setTransferData(SubscriptionCreateParams.TransferData.builder()
                            .setDestination(destinationAccountId)
                            .build())
                    .setApplicationFeePercent(applicationFeePercent)
                    .setBillingCycleAnchor(billingCycleAnchorEpochSec)
                    .setProrationBehavior(SubscriptionCreateParams.ProrationBehavior.NONE)
                    .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.ALLOW_INCOMPLETE)
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            Subscription subscription = Subscription.create(params, options);
            log.info("Stripe Subscription 作成（案b・次サイクル開始）: id={}, status={}, anchor={}, destination={}, feePct={}",
                    subscription.getId(), subscription.getStatus(), billingCycleAnchorEpochSec,
                    destinationAccountId, applicationFeePercent);
            return new SubscriptionInfo(subscription.getId(), subscription.getStatus(),
                    subscription.getCurrentPeriodEnd());
        } catch (StripeException e) {
            log.error("Stripe Subscription 作成失敗: customer={}, price={}, destination={}",
                    customerId, priceId, destinationAccountId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public void pauseSubscriptionCollection(String subscriptionId, long resumesAtEpochSec, String idempotencyKey) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            // pause_collection={behavior:'void', resumes_at:<unix_sec>}
            // void: スキップ月の invoice を void 化（paid が発火せず valid_until が延びない・設計書 02 §4.3）。
            Subscription updated = subscription.update(
                    SubscriptionUpdateParams.builder()
                            .setPauseCollection(SubscriptionUpdateParams.PauseCollection.builder()
                                    .setBehavior(SubscriptionUpdateParams.PauseCollection.Behavior.VOID)
                                    .setResumesAt(resumesAtEpochSec)
                                    .build())
                            .build(),
                    options);
            log.info("Stripe Subscription pause_collection 設定（スキップ）: id={}, status={}, resumesAt={}",
                    updated.getId(), updated.getStatus(), resumesAtEpochSec);
        } catch (StripeException e) {
            log.error("Stripe Subscription pause_collection 設定失敗: id={}, resumesAt={}", subscriptionId, resumesAtEpochSec, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public void resumeSubscriptionCollection(String subscriptionId, String idempotencyKey) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            // pause_collection を解除するには Stripe API 上 "pause_collection" を空文字列で送信する。
            // stripe-java 28.2.0 の SubscriptionUpdateParams.Builder には setPauseCollection(null) が存在しないため、
            // putExtraParam で直接 "" を渡す方式を採用する（Stripe API docs: "Pass empty string to remove" パターン）。
            Subscription updated = subscription.update(
                    SubscriptionUpdateParams.builder()
                            .putExtraParam("pause_collection", "")
                            .build(),
                    options);
            log.info("Stripe Subscription pause_collection 解除（再開）: id={}, status={}", updated.getId(), updated.getStatus());
        } catch (StripeException e) {
            log.error("Stripe Subscription pause_collection 解除失敗: id={}", subscriptionId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public SubscriptionInfo cancelSubscriptionAtPeriodEnd(String subscriptionId, String idempotencyKey) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            Subscription updated = subscription.update(
                    SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build(), options);
            log.info("Stripe Subscription 期末解約予約: id={}, status={}, periodEnd={}",
                    updated.getId(), updated.getStatus(), updated.getCurrentPeriodEnd());
            return new SubscriptionInfo(updated.getId(), updated.getStatus(), updated.getCurrentPeriodEnd());
        } catch (StripeException e) {
            log.error("Stripe Subscription 期末解約予約失敗: id={}", subscriptionId, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR);
        }
    }

    @Override
    public InvoiceWebhookEventInfo constructInvoiceEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe 継続課金 Webhook 署名検証失敗", e);
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        String eventType = event.getType();
        boolean livemode = Boolean.TRUE.equals(event.getLivemode());

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);

        String subscriptionId = null;
        String invoiceId = null;
        String invoiceStatus = null;
        String billingReason = null;
        Long amountPaidMinor = null;
        String paymentIntentId = null;
        String chargeId = null;
        Long periodStartEpochSec = null;
        Long periodEndEpochSec = null;

        if (stripeObject instanceof Invoice invoice) {
            // invoice.*（created/paid/payment_failed）: subscription 逆引き・draft 窓判定・記帳突合に必要な値を抽出。
            subscriptionId = invoice.getSubscription();
            invoiceId = invoice.getId();
            invoiceStatus = invoice.getStatus();
            billingReason = invoice.getBillingReason();
            amountPaidMinor = invoice.getAmountPaid();
            paymentIntentId = invoice.getPaymentIntent();
            chargeId = invoice.getCharge();
            periodStartEpochSec = invoice.getPeriodStart();
            periodEndEpochSec = invoice.getPeriodEnd();
        } else if (stripeObject instanceof Subscription subscription) {
            // customer.subscription.deleted: 対象 subscription のみ（他フィールドは null）。
            subscriptionId = subscription.getId();
        }

        log.info("Stripe 継続課金 Webhook 受信: id={}, type={}, subscriptionId={}, invoiceId={}, status={}, billingReason={}",
                event.getId(), eventType, subscriptionId, invoiceId, invoiceStatus, billingReason);
        return new InvoiceWebhookEventInfo(event.getId(), eventType, livemode, subscriptionId, invoiceId,
                invoiceStatus, billingReason, amountPaidMinor, paymentIntentId, chargeId,
                periodStartEpochSec, periodEndEpochSec);
    }

    @Override
    public void updateInvoiceApplicationFee(String invoiceId, long applicationFeeMinor, String idempotencyKey) {
        try {
            Invoice invoice = Invoice.retrieve(invoiceId);
            // draft 窓で application_fee_amount を固定円へ上書き（subscription の application_fee_percent 自動計算を
            // 完全上書き・PoC 実証 2026-06-05・README §4.2 / §4.4）。stripe-java 28.2.0（API 2025-02-24.acacia）固定条件。
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();
            invoice.update(InvoiceUpdateParams.builder()
                    .setApplicationFeeAmount(applicationFeeMinor)
                    .build(), options);
            log.info("Stripe invoice application_fee_amount 上書き: invoiceId={}, appFee={}", invoiceId, applicationFeeMinor);
        } catch (StripeException e) {
            // 上書き失敗は症状を隠さず例外で上申する（呼び出し側が Stripe 再送に委ねる・02 §4.2）。
            log.error("Stripe invoice application_fee_amount 上書き失敗: invoiceId={}, appFee={}",
                    invoiceId, applicationFeeMinor, e);
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    @Override
    public EscrowWebhookEventInfo constructEscrowEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Stripe Escrow Webhook 署名検証失敗", e);
            throw new BusinessException(ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        String eventType = event.getType();
        boolean livemode = Boolean.TRUE.equals(event.getLivemode());

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = deserializer.getObject().orElse(null);

        String paymentIntentId = null;
        String paymentIntentStatus = null;
        String refundId = null;
        Long refundedAmountMinor = null;
        Long chargeAmountMinor = null;
        if (stripeObject instanceof PaymentIntent pi) {
            paymentIntentId = pi.getId();
            paymentIntentStatus = pi.getStatus();
        } else if (stripeObject instanceof Charge charge) {
            // charge.refunded（設計書 02 §6.1）: PI で対象 escrow を特定し、最新 Refund・返金累計・Charge 総額を渡す。
            paymentIntentId = charge.getPaymentIntent();
            chargeAmountMinor = charge.getAmount();
            refundedAmountMinor = charge.getAmountRefunded();
            if (charge.getRefunds() != null && charge.getRefunds().getData() != null
                    && !charge.getRefunds().getData().isEmpty()) {
                List<Refund> refunds = charge.getRefunds().getData();
                refundId = refunds.get(refunds.size() - 1).getId();
            }
        }

        log.info("Stripe Escrow Webhook 受信: id={}, type={}, piStatus={}, refundId={}",
                event.getId(), eventType, paymentIntentStatus, refundId);
        return new EscrowWebhookEventInfo(event.getId(), eventType, livemode, paymentIntentId, paymentIntentStatus,
                refundId, refundedAmountMinor, chargeAmountMinor);
    }

    /**
     * Stripe {@link Account} を {@link ConnectAccountInfo} へ写す。
     */
    private ConnectAccountInfo toConnectAccountInfo(Account account) {
        boolean charges = Boolean.TRUE.equals(account.getChargesEnabled());
        boolean payouts = Boolean.TRUE.equals(account.getPayoutsEnabled());
        List<String> requirementsDue = Collections.emptyList();
        if (account.getRequirements() != null && account.getRequirements().getCurrentlyDue() != null) {
            requirementsDue = List.copyOf(account.getRequirements().getCurrentlyDue());
        }
        return new ConnectAccountInfo(charges, payouts, requirementsDue);
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
