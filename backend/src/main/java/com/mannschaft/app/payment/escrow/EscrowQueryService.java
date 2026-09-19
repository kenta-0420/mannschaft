package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.money.PaymentMoney;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** エスクローの照会、読取認可、および応答ビュー組立を担うサービス。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EscrowQueryService {

    static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final AccessControlService accessControlService;
    private final RefundRepository refundRepository;
    private final PayeeScopeResolver payeeScopeResolver;

    /**
     * 札主または受取側管理者の募集エスクロー照会を行う。札主だけに確認待ちPIのclientSecretを返し、
     * 無関係者はIDOR秘匿のため404に正規化する。既存データの読取のみで、与信やStripe書込みは行わない。
     */
    public PaymentView getRecruitmentPaymentView(EscrowSourceKind sourceKind, Long sourceId,
                                                 Long participantId, Long actorUserId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository
                .findBySourceKindAndSourceIdAndSourceParticipantId(sourceKind, sourceId, participantId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        return buildPaymentView(escrow, actorUserId);
    }

    /**
     * エスクローIDで照会する。認可とclientSecretの出し分けは募集照会と同一で、副作用はない。
     */
    public PaymentView getEscrowView(UUID escrowId, Long actorUserId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findById(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        return buildPaymentView(escrow, actorUserId);
    }

    /**
     * 受取側のエスクローをページングで返す。USERは本人限定、TEAM/ORGは管理権限を検証し、
     * 不正なscopeは403にする。clientSecretやStripe生IDは含めず、読取のみを行う。
     */
    public Page<ReceivedEscrow> listReceivedEscrows(ScopeKind scopeKind, Long scopeId,
                                                    EscrowStatus statusFilter, Long actorUserId,
                                                    Pageable pageable) {
        authorizeScopeForReceivedList(scopeKind, scopeId, actorUserId);
        ConnectAccountEntity payee = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scopeKind, scopeId)
                .orElse(null);
        if (payee == null) {
            return Page.empty(pageable);
        }
        Page<EscrowTransactionEntity> page = statusFilter == null
                ? escrowTransactionRepository.findByPayeeConnectAccountIdOrderByCreatedAtDesc(payee.getId(), pageable)
                : escrowTransactionRepository.findByPayeeConnectAccountIdAndStatusOrderByCreatedAtDesc(
                        payee.getId(), statusFilter, pageable);
        return page.map(this::toReceivedEscrow);
    }

    private PaymentView buildPaymentView(EscrowTransactionEntity escrow, Long actorUserId) {
        boolean isPayer = escrow.getPayerScopeKind() == ScopeKind.USER
                && actorUserId != null && actorUserId.equals(escrow.getPayerScopeId());
        if (isPayer) {
            boolean awaitingManualConfirm = escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION;
            boolean awaitingImmediateConfirm = escrow.getStatus() == EscrowStatus.AUTHORIZED
                    && escrow.getCaptureMode() == EscrowCaptureMode.AUTOMATIC;
            String clientSecret = null;
            if ((awaitingManualConfirm || awaitingImmediateConfirm) && escrow.getStripePaymentIntentId() != null) {
                clientSecret = stripePaymentProvider
                        .retrievePaymentIntentClientSecret(escrow.getStripePaymentIntentId()).clientSecret();
            }
            return PaymentView.forPayer(escrow, clientSecret);
        }
        authorizePayeeAdminForView(escrow, actorUserId);
        return PaymentView.forPayee(escrow);
    }

    private void authorizeScopeForReceivedList(ScopeKind scopeKind, Long scopeId, Long actorUserId) {
        if (scopeKind == ScopeKind.USER) {
            if (actorUserId == null || !actorUserId.equals(scopeId)) {
                throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
            }
            return;
        }
        try {
            switch (scopeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, scopeId,
                        payeeScopeResolver.toAccessControlScopeType(scopeKind), PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, scopeId,
                        payeeScopeResolver.toAccessControlScopeType(scopeKind), PERMISSION_MANAGE_PAYMENT);
                default -> throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() instanceof ConnectPaymentErrorCode) {
                throw e;
            }
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN, e);
        }
    }

    private void authorizePayeeAdminForView(EscrowTransactionEntity escrow, Long actorUserId) {
        ConnectAccountEntity payee = connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        ScopeKind payeeKind = payee.getScopeKind();
        if (payeeKind == ScopeKind.USER) {
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        }
        try {
            switch (payeeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                default -> throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() instanceof ConnectPaymentErrorCode) {
                throw e;
            }
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND, e);
        }
    }

    private ReceivedEscrow toReceivedEscrow(EscrowTransactionEntity escrow) {
        PaymentMoney refundedAmount = refundRepository.findByEscrowTransactionId(escrow.getId()).stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .map(refund -> new PaymentMoney(refund.getAmount(), refund.getCurrency()))
                .reduce(PaymentMoney.zero(escrow.getCurrency()), PaymentMoney::add);
        return new ReceivedEscrow(escrow.getId(), escrow.getSourceKind(), escrow.getSourceId(),
                escrow.getSourceParticipantId(), escrow.getCaptureMode(), escrow.getStatus(), escrow.getFaceAmount(),
                escrow.getAmount(), escrow.getApplicationFeeAmount(), refundedAmount.minorUnits(), escrow.getCreatedAt());
    }

    /**
     * 支払者・受取側管理者向けの照会結果。金額は最小通貨単位で、clientSecretは支払者の確認待ち時だけ設定する。
     */
    public record PaymentView(UUID escrowId, EscrowStatus status, String clientSecret,
                              long faceAmount, long chargeAmount, long applicationFeeAmount) {
        static PaymentView forPayer(EscrowTransactionEntity escrow, String clientSecret) {
            return new PaymentView(escrow.getId(), escrow.getStatus(), clientSecret, escrow.getFaceAmount(),
                    escrow.getAmount(), escrow.getApplicationFeeAmount());
        }

        static PaymentView forPayee(EscrowTransactionEntity escrow) {
            return new PaymentView(escrow.getId(), escrow.getStatus(), null, escrow.getFaceAmount(),
                    escrow.getAmount(), escrow.getApplicationFeeAmount());
        }
    }

    /**
     * 受取側一覧の行。金額と返金累計は最小通貨単位で、決済秘密情報は含めない。
     */
    public record ReceivedEscrow(UUID escrowId, EscrowSourceKind sourceKind, Long sourceId,
                                 Long sourceParticipantId, EscrowCaptureMode captureMode, EscrowStatus status,
                                 long faceAmount, long chargeAmount, long applicationFeeAmount,
                                 long refundedAmount, LocalDateTime createdAt) {}
}
