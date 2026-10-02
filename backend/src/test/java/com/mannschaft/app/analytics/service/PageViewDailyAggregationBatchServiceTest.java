package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewDailyStatsEntity;
import com.mannschaft.app.analytics.entity.PageViewLogEntity;
import com.mannschaft.app.analytics.repository.PageViewDailyStatsRepository;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.8 ページビュー日次集計バッチの結合テスト（Testcontainers MySQL）。
 *
 * <p>{@link PageViewDailyAggregationBatchService#aggregateForDate} が生ログ {@code page_view_logs} を
 * scope 単位で集計し {@code page_view_daily_stats} に正しく書き込むこと・冪等であることを、
 * 実 MySQL 上の実データで検証する（設計書 §5.2・AC-13/14/18/19/20）。</p>
 *
 * <p>test プロファイルは {@code ddl-auto: create} + {@code flyway.enabled: false} のため、
 * {@code page_view_logs} は Hibernate 生成の非パーティションテーブルになる（集計 SQL には無影響）。</p>
 */
@DisplayName("PageView 日次集計バッチ 結合テスト (F10.8)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PageViewDailyAggregationBatchServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PageViewDailyAggregationBatchService batchService;

    @Autowired
    private PageViewLogRepository logRepository;

    @Autowired
    private PageViewDailyStatsRepository dailyStatsRepository;

    private static final LocalDate TARGET = LocalDate.of(2026, 7, 1);
    private static final Long TEAM_ID = 1001L;

    @BeforeEach
    void cleanUp() {
        dailyStatsRepository.deleteAll();
        logRepository.deleteAll();
    }

    private void insertLog(Long userId, String visitorId, LocalDateTime viewedAt) {
        logRepository.saveAndFlush(PageViewLogEntity.builder()
                .scopeType(PageViewScopeType.TEAM)
                .scopeId(TEAM_ID)
                .contentType(PageViewContentType.ARTICLE)
                .contentId(4567L)
                .url("/teams/my-team/articles/4567")
                .title("春合宿のお知らせ")
                .userId(userId)
                .visitorId(visitorId)
                .viewedAt(viewedAt)
                .build());
    }

    @Test
    @DisplayName("AC-13: total/unique/member/guest が生ログと一致する")
    void aggregate_matchesRawLogs() {
        // メンバー2人（user 10, 11）＋ゲスト1人（visitor v-guest）
        insertLog(10L, "v-member-10", TARGET.atTime(9, 0));
        insertLog(11L, "v-member-11", TARGET.atTime(10, 0));
        insertLog(null, "v-guest-1", TARGET.atTime(11, 0));

        batchService.aggregateForDate(TARGET);

        PageViewDailyStatsEntity stats =
                dailyStatsRepository.findByScopeTypeAndScopeIdAndDate(PageViewScopeType.TEAM, TEAM_ID, TARGET);
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalViews()).isEqualTo(3);
        // unique: user 10, user 11, guest visitor → 3
        assertThat(stats.getUniqueVisitors()).isEqualTo(3);
        assertThat(stats.getMemberViews()).isEqualTo(2);
        assertThat(stats.getGuestViews()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-14: ゲストが同一 cookie で同日 3 回閲覧しても unique は 1")
    void aggregate_guestSameCookie_uniqueIsOne() {
        insertLog(null, "v-same-guest", TARGET.atTime(8, 0));
        insertLog(null, "v-same-guest", TARGET.atTime(12, 0));
        insertLog(null, "v-same-guest", TARGET.atTime(20, 0));

        batchService.aggregateForDate(TARGET);

        PageViewDailyStatsEntity stats =
                dailyStatsRepository.findByScopeTypeAndScopeIdAndDate(PageViewScopeType.TEAM, TEAM_ID, TARGET);
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalViews()).isEqualTo(3);
        assertThat(stats.getUniqueVisitors()).isEqualTo(1);
        assertThat(stats.getGuestViews()).isEqualTo(3);
        assertThat(stats.getMemberViews()).isZero();
    }

    @Test
    @DisplayName("AC-18: 同一日で 2 回実行しても重複しない（冪等）")
    void aggregate_idempotent() {
        insertLog(10L, "v-1", TARGET.atTime(9, 0));
        insertLog(null, "v-2", TARGET.atTime(10, 0));

        batchService.aggregateForDate(TARGET);
        batchService.aggregateForDate(TARGET);

        List<PageViewDailyStatsEntity> all = dailyStatsRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTotalViews()).isEqualTo(2);
        assertThat(all.get(0).getUniqueVisitors()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-19: aggregateForDate を過去日で呼ぶとその日の行が再計算される")
    void aggregate_backfillPastDate() {
        LocalDate past = LocalDate.of(2026, 6, 15);
        logRepository.saveAndFlush(PageViewLogEntity.builder()
                .scopeType(PageViewScopeType.TEAM)
                .scopeId(TEAM_ID)
                .contentType(PageViewContentType.PAGE)
                .contentId(0L)
                .url("/teams/my-team")
                .title("チームトップ")
                .userId(20L)
                .visitorId("v-past")
                .viewedAt(past.atTime(13, 0))
                .build());

        batchService.aggregateForDate(past);

        PageViewDailyStatsEntity stats =
                dailyStatsRepository.findByScopeTypeAndScopeIdAndDate(PageViewScopeType.TEAM, TEAM_ID, past);
        assertThat(stats).isNotNull();
        assertThat(stats.getDate()).isEqualTo(past);
        assertThat(stats.getTotalViews()).isEqualTo(1);
        assertThat(stats.getMemberViews()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-20: 生ログ 0 件の日を集計しても行は作られず例外にならない")
    void aggregate_noLogs_noRows() {
        batchService.aggregateForDate(TARGET);

        assertThat(dailyStatsRepository.findAll()).isEmpty();
        assertThat(dailyStatsRepository.findByScopeTypeAndScopeIdAndDate(
                PageViewScopeType.TEAM, TEAM_ID, TARGET)).isNull();
    }
}
