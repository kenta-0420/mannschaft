package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository.PaymentItemReceiptContext;
import com.mannschaft.app.receipt.dto.MemberPaymentReceiptDocument;
import com.mannschaft.app.receipt.service.MemberPaymentReceiptDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberPaymentReceiptPdfService {
    private final MemberPaymentRepository memberPaymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final NameResolverService nameResolverService;
    private final MemberPaymentReceiptDocumentService receiptDocumentService;
    @Qualifier("wallClock") private final Clock clock;

    public byte[] generate(Long memberPaymentId, Long requesterUserId) {
        MemberPaymentEntity payment = memberPaymentRepository.findById(memberPaymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.PAID || payment.getAmountPaid() == null
                || payment.getAmountPaid().signum() <= 0) {
            throw new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND);
        }
        if (!requesterUserId.equals(payment.getUserId()) && !requesterUserId.equals(payment.getPayerUserId())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        }
        PaymentItemReceiptContext item = paymentItemRepository.findReceiptContextById(payment.getPaymentItemId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND));
        if ((item.getTeamId() == null) == (item.getOrganizationId() == null)) {
            throw new BusinessException(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND);
        }
        String scopeType = item.getTeamId() != null ? "TEAM" : "ORGANIZATION";
        Long scopeId = item.getTeamId() != null ? item.getTeamId() : item.getOrganizationId();
        Long payerUserId = payment.getPayerUserId() != null ? payment.getPayerUserId() : payment.getUserId();
        BigDecimal amount = payment.getAmountPaid();
        return receiptDocumentService.generate(new MemberPaymentReceiptDocument(
                payment.getId(), scopeType, scopeId, payerUserId,
                nameResolverService.resolveUserDisplayName(payerUserId), item.getName(), amount,
                payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null,
                payment.getPaidAt() != null ? payment.getPaidAt().toLocalDate() : LocalDate.now(clock)));
    }
}
