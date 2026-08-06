package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 Phase 11-c {@link AdCampaignDeliveryClaimService} 実 DB 結合テスト。
 *
 * <p>claim-then-act の根拠である {@code (campaign_id, user_id, week_start)} 一意制約が、
 * 実 MySQL 上で「同時に走らせても二重確保が起きない」ことを実証する。
 * {@code AdFrequencyCapIntegrationTest} の 20 スレッド {@code CyclicBarrier} 並行テストの作法に倣う。</p>
 *
 * <p><b>クラスに {@code @Transactional} を付けない</b>。付けると全テストが 1 つの外側トランザクションに
 * 参加してしまい、各スレッドの {@code tryClaim}（{@code REQUIRES_NEW}）が実際にはコミットされず、
 * 「同時実行で一意制約が効くか」を検証できなくなる（この戦役で過去に実際に起きた誤り）。</p>
 */
@DisplayName("AdCampaignDeliveryClaimService 実DB結合テスト（claim-then-act 並行性）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdCampaignDeliveryClaimServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdCampaignDeliveryClaimService claimService;

    @Autowired
    private AdMessagingCampaignRepository campaignRepository;

    private UUID persistCampaign() {
        LocalDateTime now = LocalDateTime.now();
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(7001L)
                .name("claim結合テスト用キャンペーン")
                .status(AdCampaignStatus.DELIVERING)
                .moderationStatus(AdModerationStatus.APPROVED)
                .totalBudgetYen(50_000L)
                .consumedBudgetYen(0L)
                .startsAt(now.minusDays(1))
                .endsAt(now.plusDays(7))
                .scheduledTimezone("Asia/Tokyo")
                .createdByUserId(6001L)
                .build();
        return campaignRepository.saveAndFlush(campaign).getId();
    }

    @Test
    @DisplayName("同一 (campaignId, userId, weekStart) への並行 tryClaim は 1 回しか成功しない（原子性の実証）")
    void 並行tryClaimは1回のみ成功() throws Exception {
        UUID campaignId = persistCampaign();
        Long userId = 90001L;
        LocalDate weekStart = LocalDate.of(2026, 8, 3); // 月曜
        int concurrency = 20;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return claimService.tryClaim(campaignId, userId, weekStart);
                }));
            }
            long successCount = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    successCount++;
                }
            }

            // (campaign_id, user_id, week_start) 一意制約により、20並行呼び出しのうち成功は1回のみ
            assertThat(successCount).isEqualTo(1L);

            Set<Long> claimed = claimService.findClaimedUserIds(campaignId, weekStart);
            assertThat(claimed).containsExactly(userId);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("週をまたいだら再び claim できる（週内繰り返し配信という仕様を壊していない担保）")
    void 週をまたぐと再度claimできる() {
        UUID campaignId = persistCampaign();
        Long userId = 90002L;
        LocalDate week1 = LocalDate.of(2026, 8, 3);   // 月曜
        LocalDate week2 = week1.plusWeeks(1);          // 翌週月曜

        assertThat(claimService.tryClaim(campaignId, userId, week1)).isTrue();
        // 同一週の再 claim は失敗する
        assertThat(claimService.tryClaim(campaignId, userId, week1)).isFalse();
        // 翌週は独立した claim として成功する
        assertThat(claimService.tryClaim(campaignId, userId, week2)).isTrue();
    }

    @Test
    @DisplayName("releaseClaim で解放した claim は再取得できる（全チャネル skip 時のロールバック経路）")
    void releaseClaim後は再取得できる() {
        UUID campaignId = persistCampaign();
        Long userId = 90003L;
        LocalDate weekStart = LocalDate.of(2026, 8, 3);

        assertThat(claimService.tryClaim(campaignId, userId, weekStart)).isTrue();
        claimService.releaseClaim(campaignId, userId, weekStart);
        assertThat(claimService.tryClaim(campaignId, userId, weekStart)).isTrue();
    }

    @Test
    @DisplayName("findClaimedUserIds は未 claim ユーザーを含まない（飢餓防止の前提となる除外集合の正しさ）")
    void findClaimedUserIdsは未claimユーザーを含まない() {
        UUID campaignId = persistCampaign();
        Long claimedUser = 90004L;
        Long unclaimedUser = 90005L;
        LocalDate weekStart = LocalDate.of(2026, 8, 3);

        assertThat(claimService.tryClaim(campaignId, claimedUser, weekStart)).isTrue();

        Set<Long> claimed = claimService.findClaimedUserIds(campaignId, weekStart);
        assertThat(claimed).contains(claimedUser);
        assertThat(claimed).doesNotContain(unclaimedUser);
    }
}
