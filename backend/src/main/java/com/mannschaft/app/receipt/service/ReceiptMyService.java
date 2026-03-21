package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptPdfGenerator;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.AnnualSummaryResponse;
import com.mannschaft.app.receipt.dto.MyReceiptResponse;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import com.mannschaft.app.receipt.repository.ReceiptLineItemRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 領収書マイページサービス。メンバー自身宛の領収書取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptMyService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptLineItemRepository lineItemRepository;
    private final ReceiptPdfGenerator pdfGenerator;

    /**
     * 自分宛の領収書一覧を取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別（NULL の場合は全スコープ）
     * @param scopeId   スコープID
     * @param page      ページ番号
     * @param size      取得件数
     * @return ページネーション付き領収書一覧
     */
    public PagedResponse<MyReceiptResponse> listMyReceipts(Long userId, ReceiptScopeType scopeType,
                                                            Long scopeId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ReceiptEntity> receiptPage;

        if (scopeType != null && scopeId != null) {
            receiptPage = receiptRepository
                    .findByRecipientUserIdAndScopeTypeAndScopeIdOrderByIssuedAtDesc(
                            userId, scopeType, scopeId, pageable);
        } else {
            receiptPage = receiptRepository.findByRecipientUserIdOrderByIssuedAtDesc(userId, pageable);
        }

        List<MyReceiptResponse> data = receiptPage.getContent().stream()
                .map(r -> MyReceiptResponse.builder()
                        .id(r.getId())
                        .receiptNumber(r.getReceiptNumber())
                        .scopeName(r.getIssuerName())
                        .description(r.getDescription())
                        .amount(r.getAmount())
                        .isQualifiedInvoice(r.getIsQualifiedInvoice())
                        .paymentDate(r.getPaymentDate())
                        .issuedAt(r.getIssuedAt())
                        .isVoided(r.isVoided())
                        .pdfDownloadUrl("/api/v1/my/receipts/" + r.getId() + "/pdf")
                        .build())
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                receiptPage.getTotalElements(), page, size, receiptPage.getTotalPages());

        return PagedResponse.of(data, meta);
    }

    /**
     * 自分宛の領収書 PDF を取得する。
     *
     * @param userId    ユーザーID
     * @param receiptId 領収書ID
     * @return PDF バイト配列
     */
    public byte[] getMyReceiptPdf(Long userId, Long receiptId) {
        ReceiptEntity receipt = receiptRepository.findByIdAndRecipientUserId(receiptId, userId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.RECEIPT_NOT_FOUND));

        List<ReceiptLineItemEntity> lineItems = lineItemRepository.findByReceiptIdOrderBySortOrderAsc(receiptId);

        if (receipt.isVoided()) {
            return pdfGenerator.generateVoided(receipt, lineItems, null, null, null);
        }
        return pdfGenerator.generate(receipt, lineItems, null, null, null);
    }

    /**
     * 年間サマリーを取得する。
     *
     * @param userId    ユーザーID
     * @param year      対象年
     * @param scopeType スコープ種別（NULL の場合は全スコープ）
     * @param scopeId   スコープID
     * @return 年間サマリーレスポンス
     */
    public AnnualSummaryResponse getAnnualSummary(Long userId, int year,
                                                   ReceiptScopeType scopeType, Long scopeId) {
        List<ReceiptEntity> activeReceipts;
        if (scopeType != null && scopeId != null) {
            activeReceipts = receiptRepository.findActiveByRecipientUserIdAndYearAndScope(
                    userId, year, scopeType, scopeId);
        } else {
            activeReceipts = receiptRepository.findActiveByRecipientUserIdAndYear(userId, year);
        }

        List<ReceiptEntity> voidedReceipts = receiptRepository.findVoidedByRecipientUserIdAndYear(userId, year);

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        Map<String, BigDecimal> taxRateAmountExcl = new HashMap<>();
        Map<String, BigDecimal> taxRateTaxAmount = new HashMap<>();
        Map<String, Integer> taxRateCount = new HashMap<>();
        Map<String, AnnualSummaryResponse.ScopeSummary.ScopeSummaryBuilder> scopeMap = new HashMap<>();

        for (ReceiptEntity r : activeReceipts) {
            totalAmount = totalAmount.add(r.getAmount());
            totalTaxAmount = totalTaxAmount.add(r.getTaxAmount());

            String rateKey = r.getTaxRate().stripTrailingZeros().toPlainString() + "%";
            taxRateAmountExcl.merge(rateKey, r.getAmountExclTax(), BigDecimal::add);
            taxRateTaxAmount.merge(rateKey, r.getTaxAmount(), BigDecimal::add);
            taxRateCount.merge(rateKey, 1, Integer::sum);

            String scopeKey = r.getScopeType().name() + ":" + r.getScopeId();
            scopeMap.computeIfAbsent(scopeKey, k -> AnnualSummaryResponse.ScopeSummary.builder()
                    .scopeType(r.getScopeType().name())
                    .scopeId(r.getScopeId())
                    .scopeName(r.getIssuerName())
                    .totalAmount(BigDecimal.ZERO)
                    .count(0));
        }

        Map<String, AnnualSummaryResponse.TaxRateSummary> byTaxRate = new HashMap<>();
        for (String key : taxRateAmountExcl.keySet()) {
            byTaxRate.put(key, AnnualSummaryResponse.TaxRateSummary.builder()
                    .amountExclTax(taxRateAmountExcl.get(key))
                    .taxAmount(taxRateTaxAmount.get(key))
                    .count(taxRateCount.get(key))
                    .build());
        }

        // スコープ別集計
        Map<String, BigDecimal> scopeAmounts = new HashMap<>();
        Map<String, Integer> scopeCounts = new HashMap<>();
        for (ReceiptEntity r : activeReceipts) {
            String scopeKey = r.getScopeType().name() + ":" + r.getScopeId();
            scopeAmounts.merge(scopeKey, r.getAmount(), BigDecimal::add);
            scopeCounts.merge(scopeKey, 1, Integer::sum);
        }

        List<AnnualSummaryResponse.ScopeSummary> byScope = new ArrayList<>();
        for (Map.Entry<String, AnnualSummaryResponse.ScopeSummary.ScopeSummaryBuilder> entry : scopeMap.entrySet()) {
            byScope.add(entry.getValue()
                    .totalAmount(scopeAmounts.getOrDefault(entry.getKey(), BigDecimal.ZERO))
                    .count(scopeCounts.getOrDefault(entry.getKey(), 0))
                    .build());
        }

        BigDecimal voidedAmount = voidedReceipts.stream()
                .map(ReceiptEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return AnnualSummaryResponse.builder()
                .year(year)
                .totalAmount(totalAmount)
                .totalCount(activeReceipts.size())
                .totalTaxAmount(totalTaxAmount)
                .byTaxRate(byTaxRate)
                .byScope(byScope)
                .voidedCount(voidedReceipts.size())
                .voidedAmount(voidedAmount)
                .build();
    }
}
