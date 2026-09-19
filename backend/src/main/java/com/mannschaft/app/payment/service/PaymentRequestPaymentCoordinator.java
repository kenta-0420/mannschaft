package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Payment Request の Stripe I/O を DB transaction 外で調整する。
 *
 * <p>prepare と attach はそれぞれ {@link PaymentRequestPaymentTransactionService} の短い transaction で
 * 完了してから戻るため、Stripe の応答待ちで payment request 行ロックを保持しない。</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentRequestPaymentCoordinator {

    private final PaymentRequestPaymentTransactionService transactionService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final ConnectChargeService connectChargeService;

    public PaymentRequestPayResult pay(Long teamId, UUID requestId, Long actorUserId, String idempotencyKey) {
        PaymentRequestPaymentTransactionService.PreparedPayment payment =
                transactionService.prepare(teamId, requestId, actorUserId, idempotencyKey);

        if (payment.attemptStatus() == PaymentRequestPaymentAttemptStatus.SUCCEEDED) {
            return new PaymentRequestPayResult(payment.paymentRequestId(), payment.escrowTransactionId(), null, null);
        }
        if (payment.attemptStatus() == PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION) {
            return recoveredResult(payment);
        }

        StripeCustomerEntity customer = getOrCreateStripeCustomer(payment.payerUserId());
        MembershipChargeResult charge = connectChargeService.chargePaymentRequest(new MembershipChargeCommand(
                payment.faceAmount(),
                payment.payeeConnectAccountId(),
                customer.getStripeCustomerId(),
                payment.payerUserId(),
                teamId,
                payment.organizationId(),
                payment.stripeIdempotencyKey(),
                null,
                null,
                false,
                Map.of(
                        "paymentRequestId", payment.paymentRequestId().toString(),
                        "paymentAttemptId", payment.attemptId().toString())));
        transactionService.attach(payment.attemptId(), charge.paymentIntentId(), charge.escrowTransactionId());

        String clientSecret = charge.clientSecret();
        if (clientSecret == null) {
            clientSecret = stripePaymentProvider
                    .retrievePaymentIntentClientSecret(charge.paymentIntentId())
                    .clientSecret();
        }
        return new PaymentRequestPayResult(
                payment.paymentRequestId(), charge.escrowTransactionId(), null, clientSecret);
    }

    private PaymentRequestPayResult recoveredResult(PaymentRequestPaymentTransactionService.PreparedPayment payment) {
        StripePaymentProvider.PaymentIntentInfo intent = stripePaymentProvider
                .retrievePaymentIntentClientSecret(payment.stripePaymentIntentId());
        return new PaymentRequestPayResult(
                payment.paymentRequestId(), payment.escrowTransactionId(), null, intent.clientSecret());
    }

    private StripeCustomerEntity getOrCreateStripeCustomer(Long userId) {
        return stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String customerId = stripePaymentProvider.createCustomer("user@example.com", userId);
                    return stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(customerId)
                            .build());
                });
    }
}
