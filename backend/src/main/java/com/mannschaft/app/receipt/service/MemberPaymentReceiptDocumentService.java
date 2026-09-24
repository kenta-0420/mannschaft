package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.receipt.ReceiptPdfGenerator;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptStatus;
import com.mannschaft.app.receipt.dto.MemberPaymentReceiptDocument;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** 会費支払いのStripe hosted receiptが無い場合に、領収書ドメイン内でPDFを生成する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberPaymentReceiptDocumentService {
    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final NameResolverService nameResolverService;
    private final ReceiptPdfGenerator receiptPdfGenerator;
    @Qualifier("wallClock")
    private final Clock clock;

    public String resolveIssuerName(String scopeType, Long scopeId) {
        ReceiptScopeType type = ReceiptScopeType.fromTenantScope(scopeType);
        return issuerSettingsRepository.findByScopeTypeAndScopeId(type, scopeId)
                .map(ReceiptIssuerSettingsEntity::getIssuerName)
                .orElseGet(() -> nameResolverService.resolveScopeName(scopeType, scopeId));
    }

    public byte[] generate(MemberPaymentReceiptDocument document) {
        ReceiptScopeType scopeType = ReceiptScopeType.fromTenantScope(document.scopeType());
        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeId(scopeType, document.scopeId()).orElse(null);
        String issuerName = settings != null
                ? settings.getIssuerName()
                : nameResolverService.resolveScopeName(document.scopeType(), document.scopeId());
        BigDecimal zero = BigDecimal.ZERO;
        ReceiptEntity receipt = ReceiptEntity.builder()
                .scopeType(scopeType)
                .scopeId(document.scopeId())
                .status(ReceiptStatus.ISSUED)
                .receiptNumber("MP-" + document.memberPaymentId())
                .memberPaymentId(document.memberPaymentId())
                .recipientUserId(document.recipientUserId())
                .recipientName(document.recipientName())
                .issuerName(issuerName)
                .issuerPostalCode(settings != null ? settings.getPostalCode() : null)
                .issuerAddress(settings != null ? settings.getAddress() : null)
                .issuerPhone(settings != null ? settings.getPhone() : null)
                .isQualifiedInvoice(false)
                .description(document.description())
                .amount(document.amount())
                .taxRate(zero)
                .taxAmount(zero)
                .amountExclTax(document.amount())
                .paymentMethodLabel(document.paymentMethodLabel())
                .paymentDate(document.paymentDate())
                .issuedAt(LocalDateTime.now(clock))
                .issuedBy(SystemUsers.SYSTEM_USER_ID)
                .build();
        ReceiptLineItemEntity lineItem = ReceiptLineItemEntity.builder()
                .description(document.description())
                .amount(document.amount())
                .taxRate(zero)
                .taxAmount(zero)
                .amountExclTax(document.amount())
                .build();
        return receiptPdfGenerator.generate(receipt, List.of(lineItem), null, null,
                settings != null ? settings.getCustomFooter() : null);
    }
}
