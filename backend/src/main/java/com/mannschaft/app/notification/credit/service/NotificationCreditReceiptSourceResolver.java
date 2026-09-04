package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.receipt.PlatformReceiptSourceResolver;
import com.mannschaft.app.receipt.ReceiptSourceRef;
import com.mannschaft.app.receipt.ReceiptSourceType;
import com.mannschaft.app.receipt.dto.PlatformReceiptIssueCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 通知プリペイドクレジット購入から運営領収書の発行内容を組み立てる（F08.12 §5.2 / §5.3）。
 *
 * <p><b>通知クレジットには税額の列が無い</b>（{@code notification_credit_purchases} に
 * tax 列は 0 個）。御裁可により {@code price_jpy} を<b>税込</b>として扱い、内税で逆算する。
 * 既存購入者の支払額は変えない。丸めは {@code HALF_UP}（既存 {@code bulkCreateReceipts} に揃える）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreditReceiptSourceResolver implements PlatformReceiptSourceResolver {

    /** 内税逆算に用いる税率（%）。 */
    private static final BigDecimal TAX_RATE_PERCENT = new BigDecimal("10.00");

    private static final BigDecimal TAX_NUMERATOR = BigDecimal.TEN;
    private static final BigDecimal TAX_DENOMINATOR = new BigDecimal("110");

    private final NotificationCreditPurchaseRepository purchaseRepository;
    /**
     * 組織名の解決は共有ドメインの窓口を通す。notification から organization の Repository を
     * 直接引くと D-5「クロスドメイン Repository 依存」に抵触する
     * （{@code CrossDomainRepositoryDependencyArchTest} が検出する）。
     */
    private final NameResolverService nameResolverService;

    @Override
    public ReceiptSourceType supportedSourceType() {
        return ReceiptSourceType.NOTIFICATION_CREDIT_PURCHASE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlatformReceiptIssueCommand> resolve(ReceiptSourceRef sourceRef) {
        Optional<NotificationCreditPurchaseEntity> found =
                purchaseRepository.findById(sourceRef.asLong());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NotificationCreditPurchaseEntity purchase = found.get();
        if (purchase.getPaymentStatus() != NotificationCreditPurchaseStatus.PAID
                || purchase.getPaidAt() == null) {
            return Optional.empty();
        }

        // 領収書は「組織に対する売上」であり、購入操作をした個人宛ではない（§4.3）。
        String recipientName = nameResolverService
                .resolveOrganizationNames(java.util.List.of(purchase.getOrganizationId()))
                .get(purchase.getOrganizationId());
        if (recipientName == null) {
            log.warn("組織が見つからないため領収書の宛名を決められない purchaseId={}", purchase.getId());
            return Optional.empty();
        }

        BigDecimal amountInclTax = purchase.getPriceJpy();
        BigDecimal taxAmount = amountInclTax.multiply(TAX_NUMERATOR)
                .divide(TAX_DENOMINATOR, 0, RoundingMode.HALF_UP);
        BigDecimal amountExclTax = amountInclTax.subtract(taxAmount);

        LocalDate paymentDate = purchase.getPaidAt().toLocalDate();
        return Optional.of(new PlatformReceiptIssueCommand(
                ReceiptSourceType.NOTIFICATION_CREDIT_PURCHASE,
                sourceRef,
                recipientName,
                null,
                "通知クレジット購入代金として（" + purchase.getCreditsGranted() + " クレジット）",
                amountInclTax,
                TAX_RATE_PERCENT,
                taxAmount,
                amountExclTax,
                paymentDate,
                "クレジットカード"));
    }
}
