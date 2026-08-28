package com.mannschaft.app.advertising.service;

import org.springframework.cache.CacheManager;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import org.junit.jupiter.api.BeforeEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReportDeliveryBatchService#deliverWeeklyReports()} の統合テスト（CMP-035）。
 *
 * <p>1件の配信が失敗しても他の件が独立トランザクション（{@code REQUIRES_NEW}）で
 * コミットされることを、実 DB（MySQL Testcontainers）で検証する。
 *
 * <p>クラスレベル {@code @Transactional} は付けない。1 次キャッシュにより、独立トランザクション側
 * でコミットされた更新がこのテストのコンテキストから見えなくなる事故を避けるため（既知の罠）。
 * 検証は毎回 {@link EntityManager#clear()} 後に DB から読み直した値で行う。
 */
@DisplayName("ReportDeliveryBatchService#deliverWeeklyReports 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReportDeliveryBatchServiceIntegrationTest extends AbstractMySqlIntegrationTest {

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

    @Autowired
    private ReportDeliveryBatchService batchService;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long ADVERTISER_ACCOUNT_ID = 9950L;

    @AfterEach
    void cleanUpFixtures() {
        txTemplate.executeWithoutResult(status ->
                em.createNativeQuery("DELETE FROM ad_report_schedules WHERE advertiser_account_id = :accountId")
                        .setParameter("accountId", ADVERTISER_ACCOUNT_ID)
                        .executeUpdate());
    }

    private AdReportScheduleEntity buildSchedule(String recipientEmail) throws Exception {
        return AdReportScheduleEntity.builder()
                .advertiserAccountId(ADVERTISER_ACCOUNT_ID)
                .frequency(ReportFrequency.WEEKLY)
                .recipients(objectMapper.writeValueAsString(List.of(recipientEmail)))
                // 実在のキャンペーンを用意せずに済むよう対象キャンペーンを明示指定する
                .includeCampaigns(objectMapper.writeValueAsString(List.of(1L)))
                .enabled(true)
                .createdBy(1L)
                .build();
    }

    private LocalDateHolder lastSentAtOf(Long scheduleId) {
        Object result = em.createNativeQuery(
                        "SELECT last_sent_at FROM ad_report_schedules WHERE id = :id")
                .setParameter("id", scheduleId)
                .getSingleResult();
        return new LocalDateHolder(result);
    }

    private record LocalDateHolder(Object value) {
        boolean isNull() {
            return value == null;
        }
    }

    @Test
    @DisplayName("宛先メール形式が不正な1件が失敗しても、他の件の最終送信日時はコミットされて残る")
    void deliverWeeklyReports_途中の1件が失敗しても他の配信はコミットされる() throws Exception {
        AdReportScheduleEntity ok1 = buildSchedule("ok1@example.com");
        AdReportScheduleEntity ok2 = buildSchedule("ok2@example.com");
        // 異常系: メールアドレス形式が不正で EmailOutboxService#enqueue が同期的に例外を投げる
        AdReportScheduleEntity broken = buildSchedule("not-an-email-address");

        txTemplate.executeWithoutResult(status -> {
            em.persist(ok1);
            em.persist(broken);
            em.persist(ok2);
        });
        em.clear();

        // 本丸: バッチが例外を外に投げずに完走すること
        assertThatCode(() -> batchService.deliverWeeklyReports()).doesNotThrowAnyException();

        em.clear();

        // 正常系2件はコミットされて最終送信日時が更新されている
        assertThat(lastSentAtOf(ok1.getId()).isNull()).isFalse();
        assertThat(lastSentAtOf(ok2.getId()).isNull()).isFalse();
        // 異常系1件は失敗してロールバックされ、最終送信日時は更新されない（他へ巻き添えしない）
        assertThat(lastSentAtOf(broken.getId()).isNull()).isTrue();
    }
}
