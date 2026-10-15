package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentRequestPaymentCoordinatorTest {

    @Mock
    private PaymentRequestPaymentTransactionService transactionService;

    @Mock
    private StripeCustomerRepository stripeCustomerRepository;

    @Mock
    private StripePaymentProvider stripePaymentProvider;

    @Mock
    private ConnectChargeService connectChargeService;

    @Test
    void prepareのcommit後にStripeを呼びmetadataを渡してattachする() {
        UUID attemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();
        UUID escrowId = UUID.randomUUID();
        PaymentRequestPaymentTransactionService.PreparedPayment prepared =
                new PaymentRequestPaymentTransactionService.PreparedPayment(
                        attemptId, requestId, 1L, 3000L, "JPY", payeeId, 7L, "prpay-" + attemptId,
                        PaymentRequestPaymentAttemptStatus.CREATING, null, null);
        given(transactionService.prepare(2L, requestId, 7L, "client-key")).willReturn(prepared);
        given(stripeCustomerRepository.findByUserId(7L)).willReturn(Optional.of(StripeCustomerEntity.builder()
                .userId(7L).stripeCustomerId("cus_123").build()));
        given(connectChargeService.chargePaymentRequest(org.mockito.ArgumentMatchers.any()))
                .willReturn(new MembershipChargeResult(escrowId, "secret", "pi_123", EscrowStatus.AUTHORIZED));

        PaymentRequestPaymentCoordinator coordinator = new PaymentRequestPaymentCoordinator(
                transactionService, stripeCustomerRepository, stripePaymentProvider, connectChargeService);

        PaymentRequestPayResult result = coordinator.pay(2L, requestId, 7L, "client-key");

        ArgumentCaptor<MembershipChargeCommand> command = ArgumentCaptor.forClass(MembershipChargeCommand.class);
        verify(connectChargeService).chargePaymentRequest(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo("prpay-" + attemptId);
        assertThat(command.getValue().metadata()).containsEntry("paymentRequestId", requestId.toString())
                .containsEntry("paymentAttemptId", attemptId.toString());
        assertThat(result.escrowTransactionId()).isEqualTo(escrowId);
        InOrder order = inOrder(transactionService, connectChargeService);
        order.verify(transactionService).prepare(2L, requestId, 7L, "client-key");
        order.verify(connectChargeService).chargePaymentRequest(org.mockito.ArgumentMatchers.any());
        order.verify(transactionService).attach(attemptId, "pi_123", escrowId);
        assertThat(PaymentRequestPaymentCoordinator.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void requiresActionの再送はStripeからclientSecretを回復する() {
        UUID attemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID escrowId = UUID.randomUUID();
        PaymentRequestPaymentTransactionService.PreparedPayment prepared =
                new PaymentRequestPaymentTransactionService.PreparedPayment(
                        attemptId, requestId, 1L, 3000L, "JPY", UUID.randomUUID(), 7L, "prpay-" + attemptId,
                        PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION, "pi_123", escrowId);
        given(transactionService.prepare(2L, requestId, 7L, "client-key")).willReturn(prepared);
        given(stripePaymentProvider.retrievePaymentIntentClientSecret("pi_123"))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo("pi_123", "secret_recovered", "requires_action"));

        PaymentRequestPaymentCoordinator coordinator = new PaymentRequestPaymentCoordinator(
                transactionService, stripeCustomerRepository, stripePaymentProvider, connectChargeService);

        assertThat(coordinator.pay(2L, requestId, 7L, "client-key").clientSecret()).isEqualTo("secret_recovered");
        verify(stripePaymentProvider).retrievePaymentIntentClientSecret("pi_123");
    }
}
