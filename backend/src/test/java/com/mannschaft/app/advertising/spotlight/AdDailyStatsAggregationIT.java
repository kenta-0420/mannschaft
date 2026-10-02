package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.advertising.service.AdDailyStatsAggregationBatchService;
import com.mannschaft.app.advertising.service.MonthlyInvoiceBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.19.3 日次集計バッチ + 月次請求連携の IT（正本 §7.3・§16 AC-3.1/3.2/3.4）。
 *
 * <p>金型: {@link AbstractSpotlightIT}（Testcontainers 実 MySQL・ネイティブ SQL フィクスチャ）。
 * ddl-auto=create のため ad_daily_stats に FK 制約は無く、集計元 {@code ad_impressions}/{@code ad_clicks} と
 * pricing 参照先 {@code ad_campaigns} を native INSERT で用意する。</p>
 *
 * <p>AC 対応:</p>
 * <ul>
 *   <li>AC-3.1 CPM 2000imp/10click 単価500 → cost 1000.00・再実行冪等 / CPC 10click 単価60 → 600.00</li>
 *   <li>AC-3.2 行単位 HALF_UP（1500imp→750.00・1imp→0.50）+ 月次請求 invoice item = 750.50（単純合算）</li>
 *   <li>AC-3.4 messaging_campaign_id 非 NULL 行は集計対象外（二重課金防止）</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.3 日次集計バッチ IT")
class AdDailyStatsAggregationIT extends AbstractSpotlightIT {

    private static final String TILE = "DASHBOARD_TILE";

    @Autowired
    private AdDailyStatsAggregationBatchService aggregationBatchService;

    @Autowired
    private MonthlyInvoiceBatchService monthlyInvoiceBatchService;

    private Long advOrgId;
    private Long advAccountId;

    @BeforeEach
    void setUp() {
        setUpCommon();
        advOrgId = insertOrganization("F09193 集計広告主組織");
        advAccountId = insertAdvertiserAccount(advOrgId, "F09193 集計広告主");
        em.flush();
    }

    @Test
    @DisplayName("ac3_1_cpm: 前日 2000imp/10click・CPM単価500 → cost 1000.00・再実行で不変（冪等）")
    void ac3_1_cpm集計と冪等() {
        LocalDate day = LocalDate.now().minusDays(1);
        LocalDateTime at = day.atTime(12, 0);
        Long campaignId = insertCampaign("CPM 集計", "CPM", new BigDecimal("500.0000"));
        Long adId = insertCreative(campaignId, "cpm-creative", TILE, "ACTIVE");
        insertOperationalImpressions(adId, campaignId, at, 2000);
        insertOperationalClicks(adId, campaignId, at, 10);
        em.flush();
        em.clear();

        aggregationBatchService.aggregate(day);
        em.clear();

        assertThat(dailyStatsCount(campaignId, adId, day)).isEqualTo(1L);
        assertThat(dailyStatsCost(campaignId, adId, day)).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(dailyStatsImpressions(campaignId, adId, day)).isEqualTo(2000L);

        // 冪等: 再実行しても行数・金額不変
        aggregationBatchService.aggregate(day);
        em.clear();
        assertThat(dailyStatsCount(campaignId, adId, day)).isEqualTo(1L);
        assertThat(dailyStatsCost(campaignId, adId, day)).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("ac3_1_cpc: CPC単価60・click10 → cost 600.00")
    void ac3_1_cpc集計() {
        LocalDate day = LocalDate.now().minusDays(1);
        LocalDateTime at = day.atTime(9, 0);
        Long campaignId = insertCampaign("CPC 集計", "CPC", new BigDecimal("60.0000"));
        Long adId = insertCreative(campaignId, "cpc-creative", TILE, "ACTIVE");
        insertOperationalImpressions(adId, campaignId, at, 500); // impressions は CPC 課金に影響しない
        insertOperationalClicks(adId, campaignId, at, 10);
        em.flush();
        em.clear();

        aggregationBatchService.aggregate(day);
        em.clear();

        assertThat(dailyStatsCost(campaignId, adId, day)).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    @DisplayName("ac3_4: messaging_campaign_id 非 NULL の imp 行は集計対象外（二重課金防止）")
    void ac3_4_messaging行は集計対象外() {
        LocalDate day = LocalDate.now().minusDays(1);
        LocalDateTime at = day.atTime(15, 0);
        Long campaignId = insertCampaign("境界 集計", "CPM", new BigDecimal("500.0000"));
        Long adId = insertCreative(campaignId, "boundary-creative", TILE, "ACTIVE");
        insertOperationalImpressions(adId, campaignId, at, 2000);
        // F09.17 由来（campaign_id NULL / messaging_campaign_id 非 NULL）の混入行
        insertMessagingImpression(adId, UUID.randomUUID(), at);
        insertMessagingImpression(adId, UUID.randomUUID(), at);
        em.flush();
        em.clear();

        aggregationBatchService.aggregate(day);
        em.clear();

        // 運用型 2000 のみ集計され、messaging 2 行は混入しない
        assertThat(dailyStatsImpressions(campaignId, adId, day)).isEqualTo(2000L);
        assertThat(dailyStatsCost(campaignId, adId, day)).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("ac3_2: 行単位 HALF_UP（1500imp→750.00・1imp→0.50）+ 月次請求 invoice item = 750.50")
    void ac3_2_丸めと月次請求単純合算() {
        YearMonth month = YearMonth.now().minusMonths(2);
        LocalDate day1 = month.atDay(10);
        LocalDate day2 = month.atDay(11);
        Long campaignId = insertCampaign("端数 集計", "CPM", new BigDecimal("500.0000"));
        Long adId = insertCreative(campaignId, "rounding-creative", TILE, "ACTIVE");

        insertOperationalImpressions(adId, campaignId, day1.atTime(10, 0), 1500);
        insertOperationalImpressions(adId, campaignId, day2.atTime(10, 0), 1);
        em.flush();
        em.clear();

        aggregationBatchService.aggregate(day1);
        aggregationBatchService.aggregate(day2);
        em.clear();

        // 行単位丸め: day1=750.00 / day2=0.50
        assertThat(dailyStatsCost(campaignId, adId, day1)).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(dailyStatsCost(campaignId, adId, day2)).isEqualByComparingTo(new BigDecimal("0.50"));

        // 月次請求（対象月指定）→ invoice item 金額 = 格納済み cost の単純合算 750.50
        monthlyInvoiceBatchService.generateMonthlyInvoices(month);
        em.clear();

        Object subtotal = em.createNativeQuery(
                        "SELECT ii.subtotal FROM ad_invoice_items ii "
                                + "JOIN ad_invoices i ON i.id = ii.invoice_id "
                                + "WHERE i.advertiser_account_id = :aid AND ii.campaign_id = :cid")
                .setParameter("aid", advAccountId)
                .setParameter("cid", campaignId)
                .getSingleResult();
        assertThat(new BigDecimal(subtotal.toString()))
                .as("月次請求は格納済み cost の単純合算（再丸めなし）")
                .isEqualByComparingTo(new BigDecimal("750.50"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    /** pricing_model / unit_price_snapshot 指定の ACTIVE 運用型キャンペーンを挿入する。 */
    private Long insertCampaign(String name, String pricingModel, BigDecimal unitPriceSnapshot) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, unit_price_snapshot, created_at, updated_at) "
                                + "VALUES (:aid, :name, 'ACTIVE', :pm, :budget, "
                                + "DATE_SUB(CURDATE(), INTERVAL 90 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + ":snap, NOW(), NOW())")
                .setParameter("aid", advAccountId).setParameter("name", name)
                .setParameter("pm", pricingModel)
                .setParameter("budget", new BigDecimal("100000.00"))
                .setParameter("snap", unitPriceSnapshot)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }

    // occurred_at は必ず LocalDateTime パラメータ（:at）で bind する。
    // 集計クエリ（AdImpressionRepository.aggregateOperationalByCampaignAndAd）は窓 :start/:end を
    // LocalDateTime で bind しており、MySQL Connector/J は接続 TZ で変換する。fixture 側を TZ ナイーブな
    // 文字列リテラルで INSERT すると変換されず、CI（JVM=JST / Testcontainer=UTC）で -9h ずれて実効窓が
    // [前日15:00, 当日15:00) となり、15:00 の行が排他上限に一致して集計 0 行になる（.1 で根治済の同型 TZ バグ）。
    // 参照: memory「MySQL UTC vs JVM JST・AD 系 IT フィクスチャは JVM bind で作れ」。

    /** 運用型インプレッション（campaign_id 非 NULL）を count 行まとめて INSERT する（occurred_at は :at bind）。 */
    private void insertOperationalImpressions(Long adId, Long campaignId, LocalDateTime at, int count) {
        StringBuilder sb = new StringBuilder(
                "INSERT INTO ad_impressions (ad_id, campaign_id, occurred_at) VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // ad_id / campaign_id は整数リテラル（TZ 無関係）・occurred_at は名前付き param :at を再利用。
            sb.append('(').append(adId).append(',').append(campaignId).append(",:at)");
        }
        em.createNativeQuery(sb.toString()).setParameter("at", at).executeUpdate();
    }

    /** 運用型クリック（campaign_id 非 NULL）を count 行まとめて INSERT する（occurred_at は :at bind）。 */
    private void insertOperationalClicks(Long adId, Long campaignId, LocalDateTime at, int count) {
        StringBuilder sb = new StringBuilder(
                "INSERT INTO ad_clicks (ad_id, campaign_id, occurred_at) VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('(').append(adId).append(',').append(campaignId).append(",:at)");
        }
        em.createNativeQuery(sb.toString()).setParameter("at", at).executeUpdate();
    }

    /**
     * F09.17 由来（campaign_id NULL / messaging_campaign_id 非 NULL）のインプレッションを 1 行挿入する。
     *
     * <p>occurred_at は運用型行と同じく LocalDateTime param（:at）で bind し、集計クエリ窓と TZ 経路を揃える。
     * messaging 行は campaign_id NULL で集計除外されるため直接の TZ 影響は無いが、整合のため bind に統一する。</p>
     */
    private void insertMessagingImpression(Long adId, UUID messagingCampaignId, LocalDateTime at) {
        em.createNativeQuery(
                        "INSERT INTO ad_impressions (ad_id, campaign_id, messaging_campaign_id, occurred_at) "
                                + "VALUES (" + adId + ", NULL, UUID_TO_BIN('" + messagingCampaignId + "'), :at)")
                .setParameter("at", at)
                .executeUpdate();
    }

    private long dailyStatsCount(Long campaignId, Long adId, LocalDate date) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ad_daily_stats WHERE campaign_id = :cid AND ad_id = :adId AND date = :d")
                .setParameter("cid", campaignId).setParameter("adId", adId).setParameter("d", date)
                .getSingleResult()).longValue();
    }

    private BigDecimal dailyStatsCost(Long campaignId, Long adId, LocalDate date) {
        Object v = em.createNativeQuery(
                        "SELECT cost FROM ad_daily_stats WHERE campaign_id = :cid AND ad_id = :adId AND date = :d")
                .setParameter("cid", campaignId).setParameter("adId", adId).setParameter("d", date)
                .getSingleResult();
        return new BigDecimal(v.toString());
    }

    private long dailyStatsImpressions(Long campaignId, Long adId, LocalDate date) {
        return ((Number) em.createNativeQuery(
                        "SELECT impressions FROM ad_daily_stats WHERE campaign_id = :cid AND ad_id = :adId AND date = :d")
                .setParameter("cid", campaignId).setParameter("adId", adId).setParameter("d", date)
                .getSingleResult()).longValue();
    }
}
