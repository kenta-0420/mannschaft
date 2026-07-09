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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.8 ページビュー集計取得サービスの結合テスト（Testcontainers MySQL）。
 *
 * <p>{@link PageViewAnalyticsService#getAnalytics} が summary / daily / monthly を全フィールド非 null で
 * 組み立てること、期間省略時に全期間を返すこと（AC-12）、uniqueVisitors を生ログ直接 DISTINCT で
 * 正確に返すことを検証する（設計書 §5.3）。topContent は本サービスの責務外
 * （Controller が常に空配列にマップ・AC-15）のため本テストの対象外。</p>
 */
@DisplayName("PageView 集計取得サービス 結合テスト (F10.8)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PageViewAnalyticsServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PageViewAnalyticsService analyticsService;

    @Autowired
    private PageViewLogRepository logRepository;

    @Autowired
    private PageViewDailyStatsRepository dailyStatsRepository;

    private static final Long TEAM_ID = 2002L;
    // 保持期間(13ヶ月)内に収まるよう、直近の日付を使う（生ログ直接 DISTINCT ルートを通す）。
    private final LocalDate day1 = LocalDate.now().minusDays(2);
    private final LocalDate day2 = LocalDate.now().minusDays(1);

    @BeforeEach
    void seed() {
        dailyStatsRepository.deleteAll();
        logRepository.deleteAll();

        // 日次集計行（views/member/guest は集計テーブルから正確に取る）
        dailyStatsRepository.saveAndFlush(PageViewDailyStatsEntity.builder()
                .scopeType(PageViewScopeType.TEAM).scopeId(TEAM_ID).date(day1)
                .totalViews(3).uniqueVisitors(2).memberViews(2).guestViews(1).build());
        dailyStatsRepository.saveAndFlush(PageViewDailyStatsEntity.builder()
                .scopeType(PageViewScopeType.TEAM).scopeId(TEAM_ID).date(day2)
                .totalViews(2).uniqueVisitors(2).memberViews(1).guestViews(1).build());

        // 生ログ（summary/monthly の uniqueVisitors 正確値算出用）
        // day1: user 10（2回）, guest g1（1回） → unique 2
        insertLog(10L, "g-x", day1.atTime(9, 0));
        insertLog(10L, "g-x", day1.atTime(10, 0));
        insertLog(null, "g1", day1.atTime(11, 0));
        // day2: user 11（1回）, guest g2（1回） → unique 2
        insertLog(11L, "g-y", day2.atTime(9, 0));
        insertLog(null, "g2", day2.atTime(20, 0));
    }

    private void insertLog(Long userId, String visitorId, LocalDateTime viewedAt) {
        logRepository.saveAndFlush(PageViewLogEntity.builder()
                .scopeType(PageViewScopeType.TEAM).scopeId(TEAM_ID)
                .contentType(PageViewContentType.ARTICLE).contentId(1L)
                .url("/teams/t/articles/1").title("記事")
                .userId(userId).visitorId(visitorId).viewedAt(viewedAt).build());
    }

    @Test
    @DisplayName("AC-12: dateFrom/dateTo 省略時は全期間集計を返し、全フィールドが非 null")
    void getAnalytics_allTime_nonNull() {
        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, TEAM_ID, null, null);

        assertThat(result).isNotNull();
        assertThat(result.summary()).isNotNull();
        assertThat(result.daily()).isNotNull().hasSize(2);
        assertThat(result.monthly()).isNotNull().isNotEmpty();

        // summary は生ログ直接集計で正確値: total=5, member=3, guest=2, unique=4（user10, user11, g1, g2）
        assertThat(result.summary().totalViews()).isEqualTo(5);
        assertThat(result.summary().memberViews()).isEqualTo(3);
        assertThat(result.summary().guestViews()).isEqualTo(2);
        assertThat(result.summary().uniqueVisitors()).isEqualTo(4);
    }

    @Test
    @DisplayName("daily は日付昇順・各要素が非 null で views/uniqueVisitors を持つ")
    void getAnalytics_dailyOrdered() {
        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, TEAM_ID, day1, day2);

        assertThat(result.daily()).extracting(PageViewAnalyticsService.DailyStat::date)
                .containsExactly(day1, day2);
        assertThat(result.daily().get(0).views()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-20 相当: データ 0 件のスコープでも例外にならず 0 埋めで返る")
    void getAnalytics_emptyScope_zeros() {
        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, 99999L, null, null);

        assertThat(result).isNotNull();
        assertThat(result.summary().totalViews()).isZero();
        assertThat(result.summary().uniqueVisitors()).isZero();
        assertThat(result.daily()).isNotNull().isEmpty();
        assertThat(result.monthly()).isNotNull().isEmpty();
        assertThat(result.topContent()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("AC-P2-1/8: topContent は生ログ集計で実データ（ARTICLE/1・views 5・unique 4）を返す")
    void getAnalytics_topContent_realData() {
        // seed() は ARTICLE/contentId=1 のログを 5 件（user10x2, g1, user11, g2）投入している。
        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, TEAM_ID, null, null);

        assertThat(result.topContent()).hasSize(1);
        PageViewAnalyticsService.ContentStat top = result.topContent().get(0);
        assertThat(top.contentType()).isEqualTo("ARTICLE");
        assertThat(top.contentId()).isEqualTo(1L);
        assertThat(top.views()).isEqualTo(5L);
        assertThat(top.uniqueVisitors()).isEqualTo(4L);
    }
}
