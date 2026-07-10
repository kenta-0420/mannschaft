package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: {@link BillingPaymentGateway} の Stripe 実装。
 *
 * <p>Stripe SDK 依存は payment ドメインの {@link StripePaymentProvider} に封じ込め、本クラスは
 * (1) 決済者の Stripe Customer を get-or-create（{@code stripe_customers}・F09.13 前例）し、
 * (2) {@code Mode.SUBSCRIPTION} の Checkout 生成／期末解約予約を委譲する。Connect は用いない（D-2）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingPaymentGateway implements BillingPaymentGateway {

    private final StripePaymentProvider stripePaymentProvider;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public CheckoutSessionInfo createSubscriptionCheckout(
            Long operatorUserId, int priceJpy, String displayName, UUID contractId,
            String successUrl, String cancelUrl) {

        String stripeCustomerId = getOrCreateStripeCustomer(operatorUserId);

        StripePaymentProvider.CheckoutSessionInfo info =
                stripePaymentProvider.createBillingSubscriptionCheckoutSession(
                        stripeCustomerId, priceJpy, displayName, contractId.toString(), successUrl, cancelUrl);
        return new CheckoutSessionInfo(info.sessionId(), info.checkoutUrl());
    }

    @Override
    public Instant cancelAtPeriodEnd(String subscriptionRef) {
        StripePaymentProvider.SubscriptionInfo info = stripePaymentProvider.cancelSubscriptionAtPeriodEnd(
                subscriptionRef, "billing-cancel-" + subscriptionRef);
        Long currentPeriodEnd = info.currentPeriodEnd();
        return currentPeriodEnd == null ? null : Instant.ofEpochSecond(currentPeriodEnd);
    }

    /**
     * 決済者の Stripe Customer を取得 or 生成する（F09.13 {@code NotificationCreditCheckoutService} 前例）。
     */
    private String getOrCreateStripeCustomer(Long userId) {
        return stripeCustomerRepository.findByUserId(userId)
                .map(StripeCustomerEntity::getStripeCustomerId)
                .orElseGet(() -> {
                    UserEntity user = userRepository.findById(userId)
                            .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_999));
                    String stripeCustomerId = stripePaymentProvider.createCustomer(user.getEmail(), userId);
                    StripeCustomerEntity saved = stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(stripeCustomerId)
                            .build());
                    log.info("F20.1 決済: Stripe Customer 生成 userId={}, customerId={}", userId, stripeCustomerId);
                    return saved.getStripeCustomerId();
                });
    }
}
