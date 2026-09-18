package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.PaymentRequestPaymentAttemptEntity;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.repository.PaymentRequestPaymentAttemptRepository;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Stripe webhook を請求支払い完了の唯一の正本として反映する。 */
@Service
@RequiredArgsConstructor
public class PaymentRequestPaymentWebhookService {

    private final PaymentRequestPaymentAttemptRepository attemptRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final TeamPaymentAdvanceService teamPaymentAdvanceService;
    private final AuditLogService auditLogService;

    /** P7 の試行 metadata があるのに DB 相関が未確定なら、再送対象の例外を送出する。 */
    public void requireEscrowAttachmentForPaymentRequest(
            String paymentIntentId,
            Map<String, String> metadata) {
        String attemptId = metadata.get("paymentAttemptId");
        if (attemptId == null) {
            return;
        }
        UUID parsedAttemptId;
        try {
            parsedAttemptId = UUID.fromString(attemptId);
        } catch (IllegalArgumentException e) {
            throw new StripeWebhookRetryableException("Payment attempt metadata is invalid", e);
        }
        PaymentRequestPaymentAttemptEntity attempt = attemptRepository.findById(parsedAttemptId)
                .orElseThrow(() -> new StripeWebhookRetryableException("Payment attempt attachment is pending"));
        if (!paymentIntentId.equals(attempt.getStripePaymentIntentId()) || attempt.getEscrowTransactionId() == null) {
            throw new StripeWebhookRetryableException("Payment attempt escrow attachment is pending");
        }
    }

    @Transactional
    public void succeed(String paymentIntentId) {
        PaymentRequestPaymentAttemptEntity attempt = attemptRepository.findByStripePaymentIntentIdForUpdate(paymentIntentId)
                .orElse(null);
        if (attempt == null || attempt.getStatus() == PaymentRequestPaymentAttemptStatus.SUCCEEDED) {
            return;
        }
        PaymentRequestEntity request = paymentRequestRepository
                .findByIdAndDeletedAtIsNullForUpdate(attempt.getPaymentRequestId())
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        attempt.succeed();
        attemptRepository.save(attempt);
        if (!attempt.getId().equals(request.getCurrentPaymentAttemptId())) {
            return;
        }
        if (attempt.getEscrowTransactionId() == null) {
            throw new StripeWebhookRetryableException("Payment attempt escrow attachment is missing");
        }
        request.markAsPaid(attempt.getEscrowTransactionId());
        paymentRequestRepository.save(request);
        TeamPaymentAdvanceEntity advance = teamPaymentAdvanceService.createAdvance(
                request.getOrganizationId(), request.getPayerScopeId(), attempt.getPayerUserId(),
                attempt.getEscrowTransactionId(), attempt.getPaymentRequestId(), request.getFaceAmount(), request.getCurrency());
        auditLogService.record(
                AuditEventType.PAYMENT_REQUEST_PAID.name(),
                attempt.getPayerUserId(),
                null,
                request.getPayerScopeId(),
                request.getOrganizationId(),
                null,
                null,
                null,
                String.format("{\"paymentRequestId\":\"%s\",\"escrowId\":\"%s\",\"advanceId\":\"%s\"}",
                        attempt.getPaymentRequestId(), attempt.getEscrowTransactionId(), advance.getId()));
    }

    @Transactional
    public void noteRetryableFailure(String paymentIntentId, String failureCode) {
        PaymentRequestPaymentAttemptEntity attempt = attemptRepository
                .findByStripePaymentIntentIdForUpdate(paymentIntentId)
                .orElse(null);
        if (attempt == null
                || attempt.getStatus() == PaymentRequestPaymentAttemptStatus.SUCCEEDED
                || attempt.getStatus() == PaymentRequestPaymentAttemptStatus.FAILED) {
            return;
        }
        attempt.noteRetryableFailure(failureCode);
        attemptRepository.save(attempt);
    }

    @Transactional
    public void fail(String paymentIntentId, String failureCode) {
        PaymentRequestPaymentAttemptEntity attempt = attemptRepository.findByStripePaymentIntentIdForUpdate(paymentIntentId)
                .orElse(null);
        if (attempt == null
                || attempt.getStatus() == PaymentRequestPaymentAttemptStatus.FAILED
                || attempt.getStatus() == PaymentRequestPaymentAttemptStatus.SUCCEEDED) {
            return;
        }
        PaymentRequestEntity request = paymentRequestRepository
                .findByIdAndDeletedAtIsNullForUpdate(attempt.getPaymentRequestId())
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        attempt.fail(failureCode);
        attemptRepository.save(attempt);
        if (request.restoreAfterFailedPaymentAttempt(attempt.getId(), attempt.getPreviousStatus())) {
            paymentRequestRepository.save(request);
        }
    }
}
