package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyInvoiceBatchService {

    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final AdInvoiceRepository adInvoiceRepository;
    private final AdInvoiceItemRepository adInvoiceItemRepository;

    @Value("${mannschaft.advertising.tax-rate:10.00}")
    private BigDecimal taxRate;

    /**
     * 月次請求バッチ。毎月1日 AM 5:00 (JST) に実行。
     */
    @Scheduled(cron = "0 0 5 1 * *", zone = "Asia/Tokyo")
    @Transactional
    public void generateMonthlyInvoices() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        LocalDate monthStart = lastMonth.atDay(1);
        LocalDate monthEnd = lastMonth.atEndOfMonth();
        LocalDate invoiceMonth = monthStart;

        log.info("月次請求バッチ開始: 対象月={}", lastMonth);

        List<AdvertiserAccountEntity> activeAccounts =
                advertiserAccountRepository.findByStatus(AdvertiserAccountStatus.ACTIVE, org.springframework.data.domain.Pageable.unpaged()).getContent();

        int successCount = 0;
        int errorCount = 0;

        for (AdvertiserAccountEntity account : activeAccounts) {
            try {
                generateInvoiceForAccount(account, monthStart, monthEnd, invoiceMonth);
                successCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("請求書生成エラー: advertiserAccountId={}, error={}", account.getId(), e.getMessage(), e);
            }
        }

        log.info("月次請求バッチ完了: 成功={}, エラー={}", successCount, errorCount);
    }

    private void generateInvoiceForAccount(AdvertiserAccountEntity account, LocalDate monthStart,
                                            LocalDate monthEnd, LocalDate invoiceMonth) {
        // 既存DRAFTがあればスキップ（ISSUED/PAID/OVERDUEは更新しない）
        var existing = adInvoiceRepository.findByAdvertiserAccountIdAndInvoiceMonth(account.getId(), invoiceMonth);
        if (existing.isPresent() && existing.get().getStatus() != InvoiceStatus.DRAFT) {
            return;
        }

        // キャンペーン取得
        List<AdCampaignEntity> campaigns = adCampaignRepository.findByAdvertiserOrganizationId(account.getOrganizationId());
        if (campaigns.isEmpty()) return;

        List<Long> campaignIds = campaigns.stream().map(AdCampaignEntity::getId).toList();

        // 日次統計集計
        List<AdDailyStatsEntity> allStats = adDailyStatsRepository.findByCampaignIdsAndDateBetween(
                campaignIds, monthStart, monthEnd);
        if (allStats.isEmpty()) return;

        // キャンペーン別集計
        Map<Long, List<AdDailyStatsEntity>> byCampaign = allStats.stream()
                .collect(Collectors.groupingBy(AdDailyStatsEntity::getCampaignId));

        // 請求書作成 or 更新
        AdInvoiceEntity invoice = existing.orElseGet(() -> {
            String invoiceNumber = generateInvoiceNumber(invoiceMonth);
            return AdInvoiceEntity.builder()
                    .advertiserAccountId(account.getId())
                    .invoiceNumber(invoiceNumber)
                    .invoiceMonth(invoiceMonth)
                    .taxRate(taxRate)
                    .build();
        });

        BigDecimal totalAmount = BigDecimal.ZERO;

        // 既存明細を削除（DRAFT再生成の場合）
        if (existing.isPresent()) {
            adInvoiceItemRepository.deleteByInvoiceId(existing.get().getId());
        }

        AdInvoiceEntity savedInvoice = adInvoiceRepository.save(invoice);

        for (Map.Entry<Long, List<AdDailyStatsEntity>> entry : byCampaign.entrySet()) {
            Long campaignId = entry.getKey();
            List<AdDailyStatsEntity> campaignStats = entry.getValue();

            AdCampaignEntity campaign = campaigns.stream()
                    .filter(c -> c.getId().equals(campaignId))
                    .findFirst().orElse(null);
            if (campaign == null) continue;

            long impressions = campaignStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
            long clicks = campaignStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
            BigDecimal subtotal = campaignStats.stream()
                    .map(AdDailyStatsEntity::getCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal unitPrice = impressions > 0
                    ? subtotal.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(impressions), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            AdInvoiceItemEntity item = AdInvoiceItemEntity.builder()
                    .invoiceId(savedInvoice.getId())
                    .campaignId(campaignId)
                    .campaignName(campaign.getName())
                    .pricingModel(campaign.getPricingModel())
                    .impressions(impressions)
                    .clicks(clicks)
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build();
            adInvoiceItemRepository.save(item);

            totalAmount = totalAmount.add(subtotal);
        }

        // 合計更新
        BigDecimal taxAmount = totalAmount.multiply(taxRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        BigDecimal totalWithTax = totalAmount.add(taxAmount);

        savedInvoice.updateTotals(totalAmount, taxAmount, totalWithTax);

        // billing_method に応じた処理
        if (account.getBillingMethod() == BillingMethod.INVOICE) {
            LocalDate dueDate = YearMonth.now().plusMonths(1).atEndOfMonth();
            savedInvoice.issue();
            savedInvoice.setDueDate(dueDate);
        }
        // STRIPE の場合は Stripe Invoice API 呼び出し（TODO: Stripe連携実装）
    }

    private String generateInvoiceNumber(LocalDate invoiceMonth) {
        String prefix = String.format("INV-%d%02d-", invoiceMonth.getYear(), invoiceMonth.getMonthValue());
        // 簡易採番: 既存の最大番号+1
        long count = adInvoiceRepository.count();
        return prefix + String.format("%05d", count + 1);
    }
}
