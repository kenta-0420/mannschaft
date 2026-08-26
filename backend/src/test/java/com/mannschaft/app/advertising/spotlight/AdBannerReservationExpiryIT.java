package com.mannschaft.app.advertising.spotlight;

import org.springframework.cache.CacheManager;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.advertising.campaign.service.AdCampaignStateTransitionScheduler;
import com.mannschaft.app.advertising.campaign.service.AdFrequencyCapService;
import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.dto.SpotlightItem;
import com.mannschaft.app.advertising.service.SpotlightServingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.19.3 §7.4 / §16 AC-3.8: 予約鮮度（14 日）による serve 対象外化 + FreqCap 返却の IT。
 *
 * <p>金型: {@link AbstractSpotlightIT}（in-memory Valkey フェイク + 実 MySQL）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.3 予約 EXPIRED + serve 対象外 IT")
class AdBannerReservationExpiryIT extends AbstractSpotlightIT {

    /** ゲート開放用（{@link #openBackgroundFeatureGate()} で使う）。 */
    @Autowired
    private FeatureFlagRepository backgroundGateFeatureFlagRepository;

    /** フラグキャッシュ退避用（行を入れるだけでは isEnabled が false を返し続ける）。 */
    @Autowired
    private CacheManager backgroundGateCacheManager;

    /**
     * ゲート対象のバックグラウンド入口を open にしてから各テストを走らせる。
     *
     * <p>テストプロファイルは Flyway を無効化しており {@code feature_flags} が空のため、
     * 何もしないと {@code FeatureFlagService#isEnabled} がフェイルクローズで false を返し、
     * 検証対象のバッチ／リスナーが本体を呼ばずに正常終了してしまう。
     * 詳細は {@link FeatureFlagTestSupport} を参照。</p>
     */
    @BeforeEach
    void openBackgroundFeatureGate() {
        FeatureFlagTestSupport.enable(
                backgroundGateFeatureFlagRepository,
                backgroundGateCacheManager,
                "FEATURE_PROMOTION_ENABLED");
    }

    private static final String TILE = "DASHBOARD_TILE";
    private static final String KEY_TOTAL = "mannschaft:ad:freq:";
    private static final String KEY_PER_ADV = "mannschaft:ad:freq-adv:";

    @Autowired
    private SpotlightServingService servingService;

    @Autowired
    private AdCampaignStateTransitionScheduler scheduler;

    private Long viewerId;
    private Long advOrgId;
    private Long advAccountId;
    private Long creatorId;

    @BeforeEach
    void setUp() {
        setUpCommon();
        viewerId = insertUser("resv-viewer@example.com");
        creatorId = insertUser("resv-owner@example.com");
        advOrgId = insertOrganization("F09193 予約広告主組織");
        advAccountId = insertAdvertiserAccount(advOrgId, "F09193 予約広告主");
        insertUserAdPreferences(viewerId, true, "[]");
        em.flush();
    }

    @Test
    @DisplayName("ac3_8_serve: 14 日超過の未表示予約は serve 対象外（items 空）／新しい予約は serve される")
    void ac3_8_stale予約はserve対象外() {
        // 15 日前の予約（EXPIRED 相当）。親運用型を非 ACTIVE にして operational serving を排除する。
        ReservationFixture stale = insertReservationBanner(advOrgId, advAccountId, viewerId, TILE, creatorId);
        neutralizeOperational(stale.creativeId());
        setDeliveryCreatedAt(stale.deliveryId(), LocalDate.now().minusDays(15).atTime(12, 0));
        em.flush();
        em.clear();

        SpotlightContentResponse res = servingService.serveContent(
                viewerId, TILE, 1, "PERSONAL", null, null, null, "ja");
        assertThat(res.items())
                .as("14 日超過の予約は serve 対象外")
                .isEmpty();

        // 新しい予約（本日）→ serve される（reservation が返る）
        ReservationFixture fresh = insertReservationBanner(advOrgId, advAccountId, viewerId, TILE, creatorId);
        neutralizeOperational(fresh.creativeId());
        em.flush();
        em.clear();

        SpotlightContentResponse res2 = servingService.serveContent(
                viewerId, TILE, 1, "PERSONAL", null, null, null, "ja");
        assertThat(res2.items()).hasSize(1);
        SpotlightItem item = res2.items().get(0);
        assertThat(item.house()).isNotNull();
        assertThat(item.house().messagingCampaignId())
                .as("返るのは予約バナー（messagingCampaignId 非 null）")
                .isNotNull();
    }

    @Test
    @DisplayName("ac3_8_freqcap: EXPIRED バッチで消費週の FreqCap カウンタがデクリメントされる（0 未満にならない）")
    void ac3_8_expiredバッチでFreqCap返却() {
        LocalDateTime staleAt = LocalDate.now().minusDays(15).atTime(12, 0);
        ReservationFixture stale = insertReservationBanner(advOrgId, advAccountId, viewerId, TILE, creatorId);
        neutralizeOperational(stale.creativeId());
        setDeliveryCreatedAt(stale.deliveryId(), staleAt);
        em.flush();

        // 消費週の FreqCap キーを事前に積む（週 = created_at の週）。
        LocalDate weekStart = AdFrequencyCapService.weekStartOf(staleAt.toLocalDate());
        String totalKey = KEY_TOTAL + viewerId + ":" + weekStart;
        String perAdvKey = KEY_PER_ADV + advAccountId + ":" + viewerId + ":" + weekStart;
        redisStore.put(totalKey, "2");
        redisStore.put(perAdvKey, "2");

        int released = scheduler.expireStaleReservations();

        assertThat(released).isGreaterThanOrEqualTo(1);
        // 2 → 1（0 未満禁止のデクリメント）
        assertThat(redisStore.get(totalKey)).isEqualTo("1");
        assertThat(redisStore.get(perAdvKey)).isEqualTo("1");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 予約の親運用型キャンペーン / クリエイティブを非 ACTIVE 化し、operational serving から除外する。 */
    private void neutralizeOperational(Long creativeId) {
        Long parentCampaign = campaignIdOfCreative(creativeId);
        em.createNativeQuery("UPDATE ad_campaigns SET status = 'DRAFT' WHERE id = :id")
                .setParameter("id", parentCampaign).executeUpdate();
        em.createNativeQuery("UPDATE ads SET status = 'PAUSED' WHERE id = :id")
                .setParameter("id", creativeId).executeUpdate();
    }

    private void setDeliveryCreatedAt(String deliveryUuid, LocalDateTime createdAt) {
        em.createNativeQuery("UPDATE ad_banner_deliveries SET created_at = :ts WHERE id = UUID_TO_BIN(:did)")
                .setParameter("ts", createdAt).setParameter("did", deliveryUuid).executeUpdate();
    }
}
