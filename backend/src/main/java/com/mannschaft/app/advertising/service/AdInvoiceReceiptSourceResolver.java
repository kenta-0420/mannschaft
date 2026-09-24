package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.receipt.PlatformReceiptSourceResolver;
import com.mannschaft.app.receipt.ReceiptSourceRef;
import com.mannschaft.app.receipt.ReceiptSourceType;
import com.mannschaft.app.receipt.dto.PlatformReceiptIssueCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 広告費請求書から運営領収書の発行内容を組み立てる（F08.12 §5.2 / §5.3）。
 *
 * <p>広告費は<b>外税</b>である（{@code total_amount} が税抜、{@code total_with_tax} が税込）。
 * そのまま転記する。</p>
 *
 * <p>本クラスが advertising 側にあるのは、receipt ドメインに advertising の Repository を
 * 持ち込まないためである（モジュラーモノリスの境界）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdInvoiceReceiptSourceResolver implements PlatformReceiptSourceResolver {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    @Override
    public ReceiptSourceType supportedSourceType() {
        return ReceiptSourceType.AD_INVOICE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlatformReceiptIssueCommand> resolve(ReceiptSourceRef sourceRef) {
        Optional<AdInvoiceEntity> found = adInvoiceRepository.findById(sourceRef.asLong());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdInvoiceEntity invoice = found.get();
        if (invoice.getStatus() != InvoiceStatus.PAID || invoice.getPaidAt() == null) {
            // 未入金の請求書に領収書は出せない。空を返し、未発行として観測可能にする。
            return Optional.empty();
        }

        String recipientName = advertiserAccountRepository.findById(invoice.getAdvertiserAccountId())
                .map(account -> account.getCompanyName())
                .orElse(null);
        if (recipientName == null) {
            log.warn("広告主アカウントが見つからないため領収書の宛名を決められない invoiceId={}", invoice.getId());
            return Optional.empty();
        }

        LocalDate paymentDate = invoice.getPaidAt().toLocalDate();
        return Optional.of(new PlatformReceiptIssueCommand(
                ReceiptSourceType.AD_INVOICE,
                sourceRef,
                recipientName,
                null,
                "広告掲載料として（請求書番号 " + invoice.getInvoiceNumber() + "）",
                invoice.getTotalWithTax(),
                invoice.getTaxRate(),
                invoice.getTaxAmount(),
                invoice.getTotalAmount(),
                paymentDate,
                "クレジットカード"));
    }
}
