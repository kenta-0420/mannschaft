package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.event.MessagingCampaignBudgetConsumedEvent;
import com.mannschaft.app.advertising.campaign.repository.AdAnnouncementDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.repository.AdPushDeliveryRepository;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 Phase 11-b ε-C — 月次課金ブリッジ。
 *
 * <p>毎月 1 日 03:00 (Asia/Tokyo) に前月分の {@code ad_*_deliveries} を集計し、
 * F09.11 既存 {@code ad_invoice_items} に F09.17 由来課金行を積み上げる。
 * 既存 {@code MonthlyInvoiceBatchService}（毎月 1 日 05:00）の前に走らせることで、
 * F09.7 の請求書合計算出時に F09.17 行も含めて集計される設計とする。</p>
 *
 * <h3>単価表 (設計書 §5)</h3>
 * <ul>
 *   <li>ANNOUNCEMENT: ¥5 / 件 ({@code delivered_at IS NOT NULL})</li>
 *   <li>EMAIL:        ¥10 / 通 ({@code sent_at IS NOT NULL AND (bounce_type IS NULL OR bounce_type='SOFT')})</li>
 *   <li>PUSH:         ¥3 / 通 ({@code delivered_at IS NOT NULL AND failed_reason IS NULL})</li>
 *   <li>BANNER:       ¥3 / served view ({@code served_at IS NOT NULL} の予約行のみ。F09.19.3 §7.4 で新規実装。
 *                     クリック課金なし・未表示予約は課金対象外)</li>
 * </ul>
 *
 * <h3>冪等性</h3>
 * <p>{@code ad_invoice_items} の UNIQUE {@code (messaging_campaign_id, channel_type, month_key)} で
 * 二重積み上げを物理的に防止。本バッチを 2 回実行しても請求行は変化しない。</p>
 *
 * <h3>消費予算更新</h3>
 * <p>キャンペーン単位で集計が完了したら {@code consumed_budget_yen} を UPDATE し、
 * {@link MessagingCampaignBudgetConsumedEvent} を発行する。</p>
 *
 * <h3>トランザクション境界</h3>
 * <p>月次バッチ全体を 1 トランザクションにすると失敗時のロールバック範囲が広すぎるため、
 * キャンペーン単位で {@link Propagation#REQUIRES_NEW} を用いる。1 キャンペーンの失敗が
 * 他キャンペーンの集計に波及しない構造。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdMessagingBillingBridge {

    /** ANNOUNCEMENT 単価 (円/件)。 */
    static final long UNIT_PRICE_ANNOUNCEMENT_YEN = 5L;
    /** EMAIL 単価 (円/通)。 */
    static final long UNIT_PRICE_EMAIL_YEN = 10L;
    /** PUSH 単価 (円/通)。 */
    static final long UNIT_PRICE_PUSH_YEN = 3L;
    /** BANNER 単価 (円/served view)。F09.19.3 §7.4 固定単価。 */
    static final long UNIT_PRICE_BANNER_YEN = 3L;

    /** YYYY-MM 形式 (パーティショニング & 冪等キー)。 */
    static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AdMessagingCampaignRepository messagingCampaignRepository;
    private final AdAnnouncementDeliveryRepository announcementDeliveryRepository;
    private final AdEmailDeliveryRepository emailDeliveryRepository;
    private final AdPushDeliveryRepository pushDeliveryRepository;
    private final AdBannerDeliveryRepository bannerDeliveryRepository;
    private final AdInvoiceRepository invoiceRepository;
    private final AdInvoiceItemRepository invoiceItemRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${mannschaft.advertising.tax-rate:10.00}")
    private BigDecimal taxRate;

    /**
     * チャネル種別。Repository アクセスを束ねるための内部 enum。
     */
    enum BillingChannel {
        ANNOUNCEMENT(UNIT_PRICE_ANNOUNCEMENT_YEN),
        EMAIL(UNIT_PRICE_EMAIL_YEN),
        PUSH(UNIT_PRICE_PUSH_YEN),
        BANNER(UNIT_PRICE_BANNER_YEN);

        final long unitPriceYen;

        BillingChannel(long unitPriceYen) {
            this.unitPriceYen = unitPriceYen;
        }
    }

    /**
     * 月次バッチ本体。毎月 1 日 03:00 (Asia/Tokyo)。
     *
     * <p>F09.7 の {@code MonthlyInvoiceBatchService} (1 日 05:00) より前に走らせ、
     * 同一 invoice 内に F09.17 由来明細も含める設計。</p>
     */
    @Scheduled(cron = "${mannschaft.ad.billing.cron:0 0 3 1 * *}", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "adMessagingBilling",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT5M")
    @BatchEndpoint(name = "ad-messaging-billing-monthly",
            description = "前月分のメッセージ型広告キャンペーン配信実績を集計し、請求明細行を毎月1日03:00に積み上げる")
    public void runMonthlyBilling() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        runMonthlyBilling(targetMonth);
    }

    /**
     * 指定月を集計対象とする本体ロジック。テストから直接呼ぶための公開メソッド。
     *
     * @param targetMonth 集計対象月 (前月)
     */
    public void runMonthlyBilling(YearMonth targetMonth) {
        String monthKey = targetMonth.format(MONTH_KEY_FMT);
        log.info("F09.17 月次課金ブリッジ開始: month={}", monthKey);

        // 「前月に開始 or 配信中だったキャンペーン」を集計対象とする簡易フィルタ。
        // SCHEDULED で前月終了 / DELIVERING / COMPLETED を抽出。
        // 全件走査でも 1000 万ユーザー想定では合理的件数。
        LocalDateTime monthEnd = targetMonth.atEndOfMonth().atTime(23, 59, 59);
        // Repository が返す List は immutable な場合があるため、ここで明示的に可変リストにラップする。
        List<AdMessagingCampaign> candidates = new java.util.ArrayList<>(
                messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                        AdCampaignStatus.DELIVERING, monthEnd));
        candidates.addAll(
                messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                        AdCampaignStatus.COMPLETED, monthEnd));
        candidates.addAll(
                messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                        AdCampaignStatus.PAUSED, monthEnd));

        // 重複排除 (同一キャンペーンが複数 status で取れることは無いが安全策)
        java.util.LinkedHashMap<UUID, AdMessagingCampaign> deduped = new java.util.LinkedHashMap<>();
        for (AdMessagingCampaign c : candidates) {
            deduped.putIfAbsent(c.getId(), c);
        }

        int success = 0;
        int errors = 0;
        for (AdMessagingCampaign campaign : deduped.values()) {
            try {
                billOneCampaign(campaign, targetMonth, monthKey);
                success++;
            } catch (Exception e) {
                errors++;
                log.error("F09.17 課金ブリッジ失敗: campaignId={} error={}",
                        campaign.getId(), e.getMessage(), e);
            }
        }
        log.info("F09.17 月次課金ブリッジ完了: month={} success={} errors={}",
                monthKey, success, errors);
    }

    /**
     * 1 キャンペーン分の集計と invoice_item 積み上げを行う。
     *
     * <p>キャンペーン単位で別トランザクション ({@link Propagation#REQUIRES_NEW}) とすることで、
     * 1 件の失敗が全体集計を中断させないようにする。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void billOneCampaign(AdMessagingCampaign campaign, YearMonth targetMonth, String monthKey) {
        long announcementCount = countAnnouncements(campaign.getId(), monthKey);
        long emailCount = countBillableEmails(campaign.getId(), monthKey);
        long pushCount = countBillablePushes(campaign.getId(), monthKey);
        long bannerCount = countBillableBanners(campaign.getId(), monthKey);

        Map<BillingChannel, Long> counts = new EnumMap<>(BillingChannel.class);
        counts.put(BillingChannel.ANNOUNCEMENT, announcementCount);
        counts.put(BillingChannel.EMAIL, emailCount);
        counts.put(BillingChannel.PUSH, pushCount);
        counts.put(BillingChannel.BANNER, bannerCount);

        long totalAddedYen = 0L;
        AdInvoiceEntity invoice = null;

        for (Map.Entry<BillingChannel, Long> entry : counts.entrySet()) {
            long count = entry.getValue() != null ? entry.getValue() : 0L;
            if (count <= 0) {
                continue;
            }
            BillingChannel ch = entry.getKey();
            String channelTypeStr = ch.name();

            // 冪等性: 既に同月同チャネルの請求行があればスキップ
            Optional<AdInvoiceItemEntity> existing = invoiceItemRepository
                    .findByMessagingCampaignIdAndChannelTypeAndMonthKey(
                            campaign.getId(), channelTypeStr, monthKey);
            if (existing.isPresent()) {
                log.debug("F09.17 課金行は既に存在 (冪等スキップ): campaignId={} channel={} month={}",
                        campaign.getId(), channelTypeStr, monthKey);
                continue;
            }

            long subtotalYen = count * ch.unitPriceYen;

            // invoice をオンデマンドに取得 / 作成
            if (invoice == null) {
                invoice = getOrCreateInvoice(campaign, targetMonth);
            }

            AdInvoiceItemEntity item = AdInvoiceItemEntity.builder()
                    .invoiceId(invoice.getId())
                    // F09.17 由来は BIGINT campaign_id を持たない (V67.023 で NULL 許可化)
                    .campaignId(null)
                    .campaignName(campaign.getName())
                    // メッセージ型キャンペーンは PricingModel と直接対応しないため CPM 扱いで保存
                    // (impressions=count, unitPrice=channel 単価)
                    .pricingModel(PricingModel.CPM)
                    .impressions(count)
                    .clicks(0)
                    .unitPrice(BigDecimal.valueOf(ch.unitPriceYen))
                    .subtotal(BigDecimal.valueOf(subtotalYen))
                    .messagingCampaignId(campaign.getId())
                    .channelType(channelTypeStr)
                    .monthKey(monthKey)
                    .build();
            invoiceItemRepository.save(item);

            totalAddedYen += subtotalYen;
            log.info("F09.17 課金行追加: campaignId={} channel={} month={} count={} subtotalYen={}",
                    campaign.getId(), channelTypeStr, monthKey, count, subtotalYen);
        }

        // 請求書合計の再計算 (新規追加分のみ加算)
        if (invoice != null && totalAddedYen > 0) {
            recalcInvoiceTotals(invoice);
        }

        // consumed_budget_yen 加算 + イベント発火 (totalAddedYen=0 なら何もしない)
        if (totalAddedYen > 0) {
            long before = campaign.getConsumedBudgetYen() != null ? campaign.getConsumedBudgetYen() : 0L;
            long after = before + totalAddedYen;
            campaign.setConsumedBudgetYen(after);
            messagingCampaignRepository.save(campaign);

            eventPublisher.publishEvent(new MessagingCampaignBudgetConsumedEvent(
                    campaign.getId(),
                    campaign.getAdvertiserAccountId(),
                    totalAddedYen,
                    after,
                    campaign.getTotalBudgetYen() != null ? campaign.getTotalBudgetYen() : 0L,
                    targetMonth));
            log.info("F09.17 消費予算更新: campaignId={} addedYen={} total={}/{} month={}",
                    campaign.getId(), totalAddedYen, after,
                    campaign.getTotalBudgetYen(), targetMonth);
        }
    }

    /**
     * ANNOUNCEMENT 課金件数: delivered_at IS NOT NULL の行数。
     */
    long countAnnouncements(UUID campaignId, String monthKey) {
        return announcementDeliveryRepository
                .countByCampaignIdAndMonthKeyAndDeliveredAtIsNotNull(campaignId, monthKey);
    }

    /**
     * EMAIL 課金件数: sent_at IS NOT NULL AND (bounce_type IS NULL OR bounce_type='SOFT')。
     * HARD / COMPLAINT は課金対象外 (設計書 §11 解決事項 8)。
     */
    long countBillableEmails(UUID campaignId, String monthKey) {
        return emailDeliveryRepository.countBillableByCampaignIdAndMonthKey(campaignId, monthKey);
    }

    /**
     * PUSH 課金件数: delivered_at IS NOT NULL AND failed_reason IS NULL。
     */
    long countBillablePushes(UUID campaignId, String monthKey) {
        return pushDeliveryRepository.countBillableByCampaignIdAndMonthKey(campaignId, monthKey);
    }

    /**
     * BANNER 課金件数: served_at IS NOT NULL の予約行数（実表示された view のみ・F09.19.3 §7.4）。
     * 未表示予約（served_at NULL）・クリック有無は課金額に影響しない。
     */
    long countBillableBanners(UUID campaignId, String monthKey) {
        return bannerDeliveryRepository.countByCampaignIdAndMonthKeyAndServedAtIsNotNull(campaignId, monthKey);
    }

    /**
     * 該当広告主 × 月の請求書を取得、なければ DRAFT で新規作成する。
     * F09.7 既存と同じ {@code (advertiser_account_id, invoice_month)} で UNIQUE 想定。
     */
    AdInvoiceEntity getOrCreateInvoice(AdMessagingCampaign campaign, YearMonth targetMonth) {
        LocalDate invoiceMonth = targetMonth.atDay(1);
        return invoiceRepository
                .findByAdvertiserAccountIdAndInvoiceMonth(campaign.getAdvertiserAccountId(), invoiceMonth)
                .orElseGet(() -> {
                    String invoiceNumber = generateInvoiceNumber(invoiceMonth);
                    AdInvoiceEntity created = AdInvoiceEntity.builder()
                            .advertiserAccountId(campaign.getAdvertiserAccountId())
                            .invoiceNumber(invoiceNumber)
                            .invoiceMonth(invoiceMonth)
                            .taxRate(taxRate)
                            .status(InvoiceStatus.DRAFT)
                            .build();
                    return invoiceRepository.save(created);
                });
    }

    /**
     * 請求書合計を全明細から再集計する (F09.7 / F09.17 行を合算)。
     */
    void recalcInvoiceTotals(AdInvoiceEntity invoice) {
        BigDecimal subtotalSum = invoiceItemRepository.findByInvoiceId(invoice.getId()).stream()
                .map(AdInvoiceItemEntity::getSubtotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxAmount = subtotalSum.multiply(taxRate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        BigDecimal totalWithTax = subtotalSum.add(taxAmount);
        invoice.updateTotals(subtotalSum, taxAmount, totalWithTax);
    }

    /**
     * 請求書番号採番 (F09.7 既存と同様の簡易採番)。
     */
    String generateInvoiceNumber(LocalDate invoiceMonth) {
        String prefix = String.format("INV-%d%02d-", invoiceMonth.getYear(), invoiceMonth.getMonthValue());
        long count = invoiceRepository.count();
        return prefix + String.format("%05d", count + 1);
    }
}
