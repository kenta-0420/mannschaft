package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.PaymentRequestPaymentAttemptEntity;
import com.mannschaft.app.payment.repository.PaymentRequestPaymentAttemptRepository;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

/**
 * 決済開始前後の短い DB transaction を担う。
 *
 * <p>Stripe I/O はここに置かない。prepare の commit 後に Coordinator が Stripe を呼び、attach は別 transaction
 * で相関情報だけを永続化する。</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentRequestPaymentTransactionService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentRequestPaymentAttemptRepository attemptRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final AccessControlService accessControlService;

    @Transactional
    public PreparedPayment prepare(Long teamId, UUID requestId, Long actorUserId, String clientKey) {
        PaymentRequestEntity request = paymentRequestRepository.findByIdAndDeletedAtIsNullForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        requirePayerTeamAndAdmin(request, teamId, actorUserId);

        String clientKeyHash = sha256(clientKey);
        Optional<PaymentRequestPaymentAttemptEntity> existing =
                attemptRepository.findByPaymentRequestIdAndClientKeyHash(requestId, clientKeyHash);
        if (existing.isPresent()) {
            if (existing.get().getStatus() == PaymentRequestPaymentAttemptStatus.FAILED) {
                throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
            }
            return prepared(request, request.getPayeeConnectAccountId(), existing.get());
        }
        ConnectAccountEntity payee = requireReadyPayee(request);
        if (request.getStatus() == PaymentRequestStatus.PROCESSING) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }
        if (request.getStatus() == PaymentRequestStatus.PAID) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
        }
        if (request.getStatus() != PaymentRequestStatus.SENT
                && request.getStatus() != PaymentRequestStatus.VIEWED
                && request.getStatus() != PaymentRequestStatus.OVERDUE) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        UUID attemptId = UuidV7.generate();
        PaymentRequestPaymentAttemptEntity attempt = PaymentRequestPaymentAttemptEntity.builder()
                .organizationId(request.getOrganizationId())
                .paymentRequestId(requestId)
                .payerUserId(actorUserId)
                .clientKeyHash(clientKeyHash)
                .stripeIdempotencyKey("prpay-" + attemptId)
                .previousStatus(request.getStatus())
                .status(PaymentRequestPaymentAttemptStatus.CREATING)
                .build();
        attempt.setId(attemptId);
        request.markAsProcessing(attemptId, null);
        paymentRequestRepository.save(request);
        attemptRepository.save(attempt);
        return prepared(request, payee.getId(), attempt);
    }

    @Transactional
    public PaymentRequestPaymentAttemptEntity attach(UUID attemptId, String paymentIntentId, UUID escrowId) {
        PaymentRequestPaymentAttemptEntity attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new IllegalStateException("決済試行が見つかりません"));
        PaymentRequestEntity request = paymentRequestRepository
                .findByIdAndDeletedAtIsNullForUpdate(attempt.getPaymentRequestId())
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        if (!attemptId.equals(request.getCurrentPaymentAttemptId())) {
            throw new IllegalStateException("現在の決済試行ではありません");
        }
        if (attempt.getStatus() == PaymentRequestPaymentAttemptStatus.CREATING) {
            attempt.attachStripe(paymentIntentId, escrowId);
        }
        request.markAsProcessing(attemptId, escrowId);
        paymentRequestRepository.save(request);
        return attemptRepository.save(attempt);
    }

    private void requirePayerTeamAndAdmin(PaymentRequestEntity request, Long teamId, Long actorUserId) {
        if (request.getPayerScopeKind() != ScopeKind.TEAM || !teamId.equals(request.getPayerScopeId())) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
        }
        try {
            accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");
        } catch (BusinessException e) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM, e);
        }
    }

    private ConnectAccountEntity requireReadyPayee(PaymentRequestEntity request) {
        ConnectAccountEntity payee = connectAccountRepository.findById(request.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY));
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY);
        }
        return payee;
    }

    private PreparedPayment prepared(
            PaymentRequestEntity request,
            UUID payeeConnectAccountId,
            PaymentRequestPaymentAttemptEntity attempt) {
        return new PreparedPayment(
                attempt.getId(),
                request.getId(),
                request.getOrganizationId(),
                request.getFaceAmount().longValue(),
                request.getCurrency(),
                payeeConnectAccountId,
                attempt.getPayerUserId(),
                attempt.getStripeIdempotencyKey(),
                attempt.getStatus(),
                attempt.getStripePaymentIntentId(),
                attempt.getEscrowTransactionId());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 を利用できません", e);
        }
    }

    /** Coordinator が Stripe I/O に必要とする、commit 済みの決済開始スナップショット。 */
    public record PreparedPayment(
            UUID attemptId,
            UUID paymentRequestId,
            Long organizationId,
            long faceAmount,
            String currency,
            UUID payeeConnectAccountId,
            Long payerUserId,
            String stripeIdempotencyKey,
            PaymentRequestPaymentAttemptStatus attemptStatus,
            String stripePaymentIntentId,
            UUID escrowTransactionId) {
    }
}
