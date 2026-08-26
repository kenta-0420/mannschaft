package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.repository.PageViewDailyStatsRepository;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import com.mannschaft.app.analytics.repository.PageViewLogRepository.ContentRankingProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PageViewAnalyticsService} の topContent マッピング単体テスト（第2弾）。
 *
 * <p>Repository をモックし、{@code findTopContent} の投影行が {@code AnalyticsResult.topContent} の
 * {@link PageViewAnalyticsService.ContentStat} 配列へ正しくマップされることを検証する
 * （実 DB 集計の正しさは {@code PageViewTopContentRepositoryIT} が担保）。</p>
 */
@DisplayName("PageView 集計取得サービス topContent マッピング単体テスト (F10.8 第2弾)")
@ExtendWith(MockitoExtension.class)
class PageViewAnalyticsServiceTopContentTest {

    @Mock
    private PageViewDailyStatsRepository dailyStatsRepository;
    @Mock
    private PageViewLogRepository logRepository;

    @InjectMocks
    private PageViewAnalyticsService analyticsService;

    private static final Long TEAM_ID = 1L;

    private ContentRankingProjection projection(
            String contentType, long contentId, String title, String url, long views, long uniqueVisitors) {
        return new ContentRankingProjection() {
            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public long getContentId() {
                return contentId;
            }

            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public long getViews() {
                return views;
            }

            @Override
            public long getUniqueVisitors() {
                return uniqueVisitors;
            }
        };
    }

    @Test
    @DisplayName("findTopContent の投影行が ContentStat 配列へマップされる")
    void getAnalytics_mapsTopContent() {
        given(dailyStatsRepository.findByScopeTypeAndScopeIdAndDateBetweenOrderByDateAsc(any(), any(), any(), any()))
                .willReturn(Collections.emptyList());
        given(dailyStatsRepository.aggregateMonthlyForPeriod(any(), any(), any(), any()))
                .willReturn(Collections.emptyList());
        given(logRepository.countTotalViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countMemberViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countGuestViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countUniqueVisitors(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.findTopContent(eq(PageViewScopeType.TEAM.name()), eq(TEAM_ID),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(
                        projection("ARTICLE", 1L, "記事A", "/a/1", 30L, 20L),
                        projection("ACTIVITY", 2L, "活動B", "/act/2", 10L, 8L)));

        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, TEAM_ID, null, null);

        assertThat(result.topContent()).hasSize(2);
        PageViewAnalyticsService.ContentStat first = result.topContent().get(0);
        assertThat(first.contentType()).isEqualTo("ARTICLE");
        assertThat(first.contentId()).isEqualTo(1L);
        assertThat(first.title()).isEqualTo("記事A");
        assertThat(first.url()).isEqualTo("/a/1");
        assertThat(first.views()).isEqualTo(30L);
        assertThat(first.uniqueVisitors()).isEqualTo(20L);

        PageViewAnalyticsService.ContentStat second = result.topContent().get(1);
        assertThat(second.contentType()).isEqualTo("ACTIVITY");
        assertThat(second.contentId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-P2-4: findTopContent が空でも topContent は空リストで例外にならない")
    void getAnalytics_emptyTopContent() {
        given(dailyStatsRepository.findByScopeTypeAndScopeIdAndDateBetweenOrderByDateAsc(any(), any(), any(), any()))
                .willReturn(Collections.emptyList());
        given(dailyStatsRepository.aggregateMonthlyForPeriod(any(), any(), any(), any()))
                .willReturn(Collections.emptyList());
        given(logRepository.countTotalViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countMemberViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countGuestViews(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.countUniqueVisitors(anyString(), anyLong(), any(), any())).willReturn(0L);
        given(logRepository.findTopContent(anyString(), anyLong(), any(), any(), anyInt()))
                .willReturn(Collections.emptyList());

        PageViewAnalyticsService.AnalyticsResult result =
                analyticsService.getAnalytics(PageViewScopeType.TEAM, TEAM_ID, null, null);

        assertThat(result.topContent()).isNotNull().isEmpty();
    }
}
