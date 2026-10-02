package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.PaymentRequestPaymentAttemptEntity;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.repository.PaymentRequestPaymentAttemptRepository;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentRequestPaymentWebhookServiceTest {

    @Mock
    private PaymentRequestPaymentAttemptRepository attemptRepository;
    @Mock
    private PaymentRequestRepository paymentRequestRepository;
    @Mock
    private TeamPaymentAdvanceService teamPaymentAdvanceService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PaymentRequestPaymentAttemptEntity attempt;
    @Mock
    private PaymentRequestEntity request;
    @Mock
    private TeamPaymentAdvanceEntity advance;

    @Test
    void metadataがP7試行を示すのにattempt未登録ならwebhook再送対象にする() {
        UUID attemptId = UUID.randomUUID();
        given(attemptRepository.findById(attemptId)).willReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service()
                        .requireEscrowAttachmentForPaymentRequest(
                                "pi_early", Map.of("paymentAttemptId", attemptId.toString())))
                .isInstanceOf(StripeWebhookRetryableException.class);
    }

    @Test
    void attemptにPaymentIntentとescrowが相関済みならwebhook処理を許可する() {
        UUID attemptId = UUID.randomUUID();
        given(attemptRepository.findById(attemptId)).willReturn(Optional.of(attempt));
        given(attempt.getStripePaymentIntentId()).willReturn("pi_attached");
        given(attempt.getEscrowTransactionId()).willReturn(UUID.randomUUID());

        service().requireEscrowAttachmentForPaymentRequest(
                "pi_attached", Map.of("paymentAttemptId", attemptId.toString()));

        verify(attemptRepository).findById(attemptId);
    }

    @Test
    void succeededWebhookAloneFinalizesPaidAndCreatesOneAdvance() {
        UUID attemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID escrowId = UUID.randomUUID();
        given(attemptRepository.findByStripePaymentIntentIdForUpdate("pi_123")).willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);
        given(attempt.getPaymentRequestId()).willReturn(requestId);
        given(attempt.getId()).willReturn(attemptId);
        given(attempt.getEscrowTransactionId()).willReturn(escrowId);
        given(paymentRequestRepository.findByIdAndDeletedAtIsNullForUpdate(requestId)).willReturn(Optional.of(request));
        given(request.getCurrentPaymentAttemptId()).willReturn(attemptId);
        given(request.getOrganizationId()).willReturn(1L);
        given(request.getPayerScopeId()).willReturn(2L);
        given(request.getFaceAmount()).willReturn(3000);
        given(request.getCurrency()).willReturn("JPY");
        given(attempt.getPayerUserId()).willReturn(3L);
        given(teamPaymentAdvanceService.createAdvance(1L, 2L, 3L, escrowId, requestId, 3000, "JPY"))
                .willReturn(advance);

        service().succeed("pi_123");

        verify(attempt).succeed();
        verify(request).markAsPaid(escrowId);
        verify(teamPaymentAdvanceService).createAdvance(1L, 2L, 3L, escrowId, requestId, 3000, "JPY");
    }

    @Test
    void succeededWebhookの再送はadvanceを再作成しない() {
        given(attemptRepository.findByStripePaymentIntentIdForUpdate("pi_done")).willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.SUCCEEDED);

        service().succeed("pi_done");

        verify(attempt, never()).succeed();
        verify(teamPaymentAdvanceService, never()).createAdvance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedOldAttemptCannotRestoreCurrentProcessingRequest() {
        UUID oldAttemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        given(attemptRepository.findByStripePaymentIntentIdForUpdate("pi_old")).willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);
        given(attempt.getPaymentRequestId()).willReturn(requestId);
        given(attempt.getId()).willReturn(oldAttemptId);
        given(attempt.getPreviousStatus()).willReturn(PaymentRequestStatus.SENT);
        given(paymentRequestRepository.findByIdAndDeletedAtIsNullForUpdate(requestId)).willReturn(Optional.of(request));
        given(request.restoreAfterFailedPaymentAttempt(oldAttemptId, PaymentRequestStatus.SENT)).willReturn(false);

        service().fail("pi_old", "payment_failed");

        verify(attempt).fail("payment_failed");
        verify(request, never()).markAsPaid(org.mockito.ArgumentMatchers.any());
        verify(paymentRequestRepository, never()).save(request);
    }

    @Test
    void paymentFailedはattemptを終端化せず同じPaymentIntentの再試行を許す() {
        given(attemptRepository.findByStripePaymentIntentIdForUpdate("pi_retry"))
                .willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);

        service().noteRetryableFailure("pi_retry", "payment_intent.payment_failed");

        verify(attempt).noteRetryableFailure("payment_intent.payment_failed");
        verify(attemptRepository).save(attempt);
        verify(attempt, never()).fail("payment_intent.payment_failed");
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    void failedWebhookは現在attemptだけを開始前状態へ戻す() {
        UUID attemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        given(attemptRepository.findByStripePaymentIntentIdForUpdate("pi_failed")).willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);
        given(attempt.getPaymentRequestId()).willReturn(requestId);
        given(attempt.getId()).willReturn(attemptId);
        given(attempt.getPreviousStatus()).willReturn(PaymentRequestStatus.OVERDUE);
        given(paymentRequestRepository.findByIdAndDeletedAtIsNullForUpdate(requestId)).willReturn(Optional.of(request));
        given(request.restoreAfterFailedPaymentAttempt(attemptId, PaymentRequestStatus.OVERDUE)).willReturn(true);

        service().fail("pi_failed", "payment_intent_canceled");

        verify(attempt).fail("payment_intent_canceled");
        verify(paymentRequestRepository).save(request);
        verify(teamPaymentAdvanceService, never()).createAdvance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    private PaymentRequestPaymentWebhookService service() {
        return new PaymentRequestPaymentWebhookService(
                attemptRepository,
                paymentRequestRepository,
                teamPaymentAdvanceService,
                auditLogService);
    }
}
