package com.mannschaft.app.advertising.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
     * 月次請求バッチ。毎月1日 AM 5:00 (JST) に実行（前月固定）。
     *
     * <p>cron / {@code @BatchEndpoint}（パラメータ無し実行）は本 no-arg 側に維持し、
     * 対象月を指定するリラン（F09.19.3 {@code POST /spotlight/invoices/run}）は
     * {@link #generateMonthlyInvoices(YearMonth)} を直接呼ぶ。</p>
     */
    @BatchEndpoint(name = "advertising-invoice-monthly-generate", description = "前月分の広告主月次請求書を毎月 1 日 05:00 に生成する")
    @Scheduled(cron = "0 0 5 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "monthlyInvoiceGenerate", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void generateMonthlyInvoices() {
        generateMonthlyInvoices(YearMonth.now().minusMonths(1));
    }

    /**
     * 指定月を対象に月次請求書を生成する（F09.19.3 §16 AC-3.7。当日中の E2E クローズを可能にする）。
     *
     * <p>冪等性は既存の「DRAFT のみ再生成・ISSUED/PAID/OVERDUE 不変」規則に従う。cost の再丸めは行わず、
     * {@code ad_daily_stats.cost} の単純合算で明細金額を確定する（正本 §7.3・§16 AC-3.2）。</p>
     *
     * @param targetMonth 集計対象月
     */
    @Transactional
    public void generateMonthlyInvoices(YearMonth targetMonth) {
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate monthEnd = targetMonth.atEndOfMonth();
        LocalDate invoiceMonth = monthStart;

        log.info("月次請求バッチ開始: 対象月={}", targetMonth);

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

        // キャンペーン取得（F09.19.5: advertiser_account_id 直結。従来は account.getScopeId() を
        // advertiser_organization_id に流用しており TEAM 広告主のキャンペーンが 1 件も載らないバグがあった）
        List<AdCampaignEntity> campaigns = adCampaignRepository.findByAdvertiserAccountId(account.getId());
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
        if (account.getBillingMethod() == BillingMethod.STRIPE && account.getStripeCustomerId() != null) {
            try {
                createStripeInvoice(account, savedInvoice, byCampaign);
            } catch (Exception e) {
                log.error("Stripe Invoice 作成エラー: accountId={}, error={}", account.getId(), e.getMessage(), e);
                // status = DRAFT のまま保持、次回手動リトライで対応
            }
        }
    }

    private void createStripeInvoice(AdvertiserAccountEntity account, AdInvoiceEntity invoice,
                                      Map<Long, List<AdDailyStatsEntity>> byCampaign) {
        try {
            com.stripe.param.InvoiceCreateParams.Builder invoiceParams = com.stripe.param.InvoiceCreateParams.builder()
                    .setCustomer(account.getStripeCustomerId())
                    .setAutoAdvance(true)
                    .setCollectionMethod(com.stripe.param.InvoiceCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY);

            com.stripe.model.Invoice stripeInvoice = com.stripe.model.Invoice.create(invoiceParams.build());

            // 明細行を追加
            for (var entry : byCampaign.entrySet()) {
                BigDecimal subtotal = entry.getValue().stream()
                        .map(AdDailyStatsEntity::getCost)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                long amountInYen = subtotal.setScale(0, java.math.RoundingMode.HALF_UP).longValue();

                com.stripe.param.InvoiceItemCreateParams itemParams = com.stripe.param.InvoiceItemCreateParams.builder()
                        .setCustomer(account.getStripeCustomerId())
                        .setInvoice(stripeInvoice.getId())
                        .setAmount(amountInYen)
                        .setCurrency("jpy")
                        .setDescription("Campaign " + entry.getKey())
                        .build();
                com.stripe.model.InvoiceItem.create(itemParams);
            }

            // Stripe Invoice を確定（自動送信）
            stripeInvoice.finalizeInvoice();

            // stripe_invoice_id を保存
            invoice.setStripeInvoiceId(stripeInvoice.getId());
            log.info("Stripe Invoice 作成成功: stripeInvoiceId={}, invoiceId={}", stripeInvoice.getId(), invoice.getId());
        } catch (com.stripe.exception.StripeException e) {
            throw new RuntimeException("Stripe Invoice creation failed", e);
        }
    }

    private String generateInvoiceNumber(LocalDate invoiceMonth) {
        String prefix = String.format("INV-%d%02d-", invoiceMonth.getYear(), invoiceMonth.getMonthValue());
        // 簡易採番: 既存の最大番号+1
        long count = adInvoiceRepository.count();
        return prefix + String.format("%05d", count + 1);
    }
}
