package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdClickRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdImpressionRepository;
import com.mannschaft.app.advertising.repository.AdRateCardRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F09.19.3 日次集計バッチ（欠落環 #3 の充填。正本 §7.3・§16 AC-3.1/3.2/3.4）。
 *
 * <p>前日分の {@code ad_impressions} / {@code ad_clicks} を <b>運用型（{@code campaign_id IS NOT NULL}）に限定</b>して
 * {@code (campaign_id, ad_id)} で集約し、{@code ad_daily_stats} へ UPSERT する。既存 {@code uk_campaign_ad_date} により
 * 再実行冪等。F09.17 分（{@code messaging_campaign_id} 非 NULL 行）は集計対象外で、両バッチの課金境界は
 * {@code ad_impressions.campaign_id} の NULL/非 NULL で排他になる（二重課金防止・AC-3.4）。</p>
 *
 * <h3>cost 算定と丸め</h3>
 * <ul>
 *   <li>{@code CPM} → {@code impressions / 1000 × unit_price}</li>
 *   <li>{@code CPC} → {@code clicks × unit_price}</li>
 *   <li>単価は {@code ad_campaigns.unit_price_snapshot}。NULL（V144.002 以前の理論上の既存行のみ）なら集計日に有効な
 *       全国・全テンプレートカードで代替し、それも無ければ cost=0 + WARN + メトリクス {@code ad_daily_stats_unpriced_rows}</li>
 *   <li><b>行単位で {@code setScale(2, HALF_UP)}</b>（DECIMAL(12,2) と整合。月次請求は格納済み cost の単純合算・再丸めなし）</li>
 * </ul>
 *
 * <p>{@code @Transactional} は advertising ドメイン内で完結する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdDailyStatsAggregationBatchService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String METRIC_UNPRICED = "ad_daily_stats_unpriced_rows";

    private final AdImpressionRepository adImpressionRepository;
    private final AdClickRepository adClickRepository;
    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final AdRateCardRepository adRateCardRepository;
    private final MeterRegistry meterRegistry;

    /**
     * 日次集計バッチ本体。毎日 01:30 (Asia/Tokyo)。前日分を集計する。
     *
     * <p>毎月 1 日の 03:00（{@code AdMessagingBillingBridge}）/ 05:00（月次請求）より前に配置し、
     * それらが前日までの確定値を参照できる順序を保証する（正本 §7.3）。</p>
     */
    @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "adDailyStatsAggregation",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void runDailyAggregation() {
        LocalDate targetDate = LocalDate.now(ZONE).minusDays(1);
        aggregate(targetDate);
    }

    /**
     * 指定日 {@code [00:00, 24:00)} の運用型インプレッション / クリックを集計して {@code ad_daily_stats} へ UPSERT する。
     * 手動トリガー（{@code POST /api/v1/system-admin/spotlight/daily-stats/run}）からも呼ばれる。冪等。
     *
     * @param targetDate 集計対象日（Asia/Tokyo のカレンダー日）
     */
    @Transactional
    public void aggregate(LocalDate targetDate) {
        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.plusDays(1).atStartOfDay();
        log.info("F09.19.3 日次集計開始: targetDate={}", targetDate);

        // (campaignId, adId) → [impressions, clicks] を集約。imp/click 双方から鍵を集める。
        Map<Key, long[]> counts = new HashMap<>();
        for (Object[] row : adImpressionRepository.aggregateOperationalByCampaignAndAd(start, end)) {
            Key key = new Key(toLong(row[0]), toLong(row[1]));
            counts.computeIfAbsent(key, k -> new long[2])[0] = toLong(row[2]);
        }
        for (Object[] row : adClickRepository.aggregateOperationalByCampaignAndAd(start, end)) {
            Key key = new Key(toLong(row[0]), toLong(row[1]));
            counts.computeIfAbsent(key, k -> new long[2])[1] = toLong(row[2]);
        }

        Map<Long, AdCampaignEntity> campaignCache = new HashMap<>();
        int upserted = 0;
        int unpriced = 0;
        for (Map.Entry<Key, long[]> entry : counts.entrySet()) {
            Key key = entry.getKey();
            long impressions = entry.getValue()[0];
            long clicks = entry.getValue()[1];

            AdCampaignEntity campaign = campaignCache.computeIfAbsent(
                    key.campaignId(), id -> adCampaignRepository.findById(id).orElse(null));
            if (campaign == null) {
                // キャンペーンが物理削除等で不在の異常行はスキップ（集計対象外）。
                log.warn("F09.19.3 集計: キャンペーン不在のためスキップ campaignId={} adId={}",
                        key.campaignId(), key.adId());
                continue;
            }

            BigDecimal unitPrice = resolveUnitPrice(campaign, targetDate);
            if (unitPrice == null) {
                unpriced++;
                unitPrice = BigDecimal.ZERO;
            }
            BigDecimal cost = computeCost(campaign.getPricingModel(), impressions, clicks, unitPrice);

            adDailyStatsRepository.upsertDailyStat(
                    key.campaignId(), key.adId(), targetDate, impressions, clicks, cost);
            upserted++;
        }

        if (unpriced > 0) {
            // 請求漏れ検知メトリクス（正本 §7.3・§19）。
            meterRegistry.counter(METRIC_UNPRICED).increment(unpriced);
            log.warn("F09.19.3 集計: 単価未確定行あり targetDate={} unpricedRows={}", targetDate, unpriced);
        }
        log.info("F09.19.3 日次集計完了: targetDate={} upsertedRows={} unpricedRows={}",
                targetDate, upserted, unpriced);
    }

    /**
     * 行単位 cost を算定し {@code setScale(2, HALF_UP)} で 2 桁に丸める（正本 §7.3・§16 AC-3.2）。
     */
    BigDecimal computeCost(PricingModel pricingModel, long impressions, long clicks, BigDecimal unitPrice) {
        BigDecimal raw;
        if (pricingModel == PricingModel.CPC) {
            raw = unitPrice.multiply(BigDecimal.valueOf(clicks));
        } else {
            // CPM（既定）: impressions / 1000 × unit_price
            raw = unitPrice.multiply(BigDecimal.valueOf(impressions)).divide(THOUSAND, 10, RoundingMode.HALF_UP);
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 単価解決: スナップショット優先、NULL なら全国・全テンプレートカードで代替、無ければ null。
     */
    private BigDecimal resolveUnitPrice(AdCampaignEntity campaign, LocalDate targetDate) {
        if (campaign.getUnitPriceSnapshot() != null) {
            return campaign.getUnitPriceSnapshot();
        }
        List<AdRateCardEntity> fallback =
                adRateCardRepository.findNationwideDefaultRates(campaign.getPricingModel(), targetDate);
        if (!fallback.isEmpty()) {
            return fallback.get(0).getUnitPrice();
        }
        return null;
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 集計鍵（campaignId, adId）。 */
    private record Key(Long campaignId, Long adId) {
    }
}
