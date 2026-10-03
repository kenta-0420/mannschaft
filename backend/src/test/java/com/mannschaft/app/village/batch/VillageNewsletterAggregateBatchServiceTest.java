package com.mannschaft.app.village.batch;

import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.service.NewsletterDigestSnapshot;
import com.mannschaft.app.village.service.VillageNewsletterDigestAggregator;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageNewsletterAggregateBatchService} 単体テスト（F17.1 ②-2・設計書 §11.1）。
 *
 * <p>集計日判定（WEEKLY 曜日 / MONTHLY 日付・月末番兵 0）と、<b>越境読み取りをトランザクション外で
 * 済ませてから村ドメインで凍結する</b>フローを検証する。特に <b>AC-03 のバッチ版</b>＝既存号があるとき
 * 集計器（越境読み取り）を呼ばないこと（{@code verify(aggregator, never())}）を担保する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-03（バッチ版）: 既存号があるとき aggregate も freezeIssue も呼ばれない</li>
 *   <li>集計日判定: WEEKLY は曜日一致時のみ、MONTHLY は日付一致（0=月末）時のみ集計する</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNewsletterAggregateBatchService 単体テスト（F17.1 ②-2）")
class VillageNewsletterAggregateBatchServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000d01");
    private static final LocalDate WEEKLY_TODAY = LocalDate.of(2026, 6, 15);
    private static final NewsletterDigestSnapshot SNAPSHOT =
            new NewsletterDigestSnapshot(5, 1, 0, 0, 0, List.of());

    @Mock
    private VillageNewsletterRepository newsletterRepository;
    @Mock
    private VillageNewsletterIssueRepository issueRepository;
    @Mock
    private VillageNewsletterIssueService issueService;
    @Mock
    private VillageNewsletterDigestAggregator aggregator;

    @InjectMocks
    private VillageNewsletterAggregateBatchService batchService;

    private void stubNewsletters(VillageNewsletterFrequency freq, VillageNewsletterEntity... nls) {
        for (VillageNewsletterFrequency f : VillageNewsletterFrequency.values()) {
            given(newsletterRepository.findByFrequencyAndIsEnabledTrueAndDeletedAtIsNull(f))
                    .willReturn(f == freq ? List.of(nls) : List.of());
        }
    }

    // ========================================================================
    // 集計日一致 → 集計器（取引外）→ 凍結
    // ========================================================================

    @Test
    @DisplayName("WEEKLY 集計日一致・既存号なし → aggregate（取引外）→ freezeIssue が呼ばれる")
    void aggregateForDate_weeklyOnAggregateDay_aggregatesAndFreezes() {
        int aggregateDay = WEEKLY_TODAY.getDayOfWeek().getValue();
        stubNewsletters(VillageNewsletterFrequency.WEEKLY, weekly(aggregateDay));
        given(issueRepository
                .findFirstByVillageIdAndFrequencyAndPeriodEndLessThanAndDeletedAtIsNullOrderByPeriodEndDesc(
                        any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.empty());
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(
                any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.empty());
        given(aggregator.aggregate(eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(SNAPSHOT);

        int aggregated = batchService.aggregateForDate(WEEKLY_TODAY);

        assertThat(aggregated).isEqualTo(1);
        verify(aggregator).aggregate(eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(issueService).freezeIssue(
                eq(VILLAGE_ID), eq(VillageNewsletterFrequency.WEEKLY), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(SNAPSHOT));
    }

    @Test
    @DisplayName("MONTHLY 月末番兵(0) は当日が月末なら集計する")
    void aggregateForDate_monthEndSentinel_aggregates() {
        LocalDate monthEnd = LocalDate.of(2026, 2, 28); // 2026 は平年・2月末=28
        stubNewsletters(VillageNewsletterFrequency.MONTHLY, monthly(0));
        given(issueRepository
                .findFirstByVillageIdAndFrequencyAndPeriodEndLessThanAndDeletedAtIsNullOrderByPeriodEndDesc(
                        any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.empty());
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(
                any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.empty());
        given(aggregator.aggregate(eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(SNAPSHOT);

        int aggregated = batchService.aggregateForDate(monthEnd);

        assertThat(aggregated).isEqualTo(1);
        verify(issueService).freezeIssue(
                eq(VILLAGE_ID), eq(VillageNewsletterFrequency.MONTHLY), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(SNAPSHOT));
    }

    // ========================================================================
    // 集計日でない → 何もしない
    // ========================================================================

    @Test
    @DisplayName("WEEKLY 集計日でない → aggregate も freezeIssue も呼ばれない")
    void aggregateForDate_notAggregateDay_skips() {
        int wrongDay = WEEKLY_TODAY.getDayOfWeek().getValue() % 7 + 1; // 当日と異なる曜日
        stubNewsletters(VillageNewsletterFrequency.WEEKLY, weekly(wrongDay));

        int aggregated = batchService.aggregateForDate(WEEKLY_TODAY);

        assertThat(aggregated).isEqualTo(0);
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(issueService, never())
                .freezeIssue(any(), any(), any(), any(), any(), any(), any());
    }

    // ========================================================================
    // AC-03（バッチ版）— 既存号があれば越境読み取りすらしない
    // ========================================================================

    @Test
    @DisplayName("AC-03: 既存号があるとき aggregate（越境読み取り）も freezeIssue も呼ばれない")
    void aggregateForDate_existingIssue_skipsAggregation() {
        int aggregateDay = WEEKLY_TODAY.getDayOfWeek().getValue();
        stubNewsletters(VillageNewsletterFrequency.WEEKLY, weekly(aggregateDay));
        given(issueRepository
                .findFirstByVillageIdAndFrequencyAndPeriodEndLessThanAndDeletedAtIsNullOrderByPeriodEndDesc(
                        any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.empty());
        // 既存号あり
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(
                any(), any(), any(LocalDateTime.class)))
                .willReturn(Optional.of(existingIssue()));

        int aggregated = batchService.aggregateForDate(WEEKLY_TODAY);

        assertThat(aggregated).isEqualTo(0);
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(issueService, never())
                .freezeIssue(any(), any(), any(), any(), any(), any(), any());
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageNewsletterEntity weekly(int aggregateDay) {
        return VillageNewsletterEntity.builder()
                .villageId(VILLAGE_ID)
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .isEnabled(true)
                .aggregateDay(aggregateDay)
                .dispatchDay(5)
                .dispatchHour(18)
                .version(0L)
                .build();
    }

    private VillageNewsletterEntity monthly(int aggregateDay) {
        return VillageNewsletterEntity.builder()
                .villageId(VILLAGE_ID)
                .frequency(VillageNewsletterFrequency.MONTHLY)
                .isEnabled(true)
                .aggregateDay(aggregateDay)
                .dispatchDay(0)
                .dispatchHour(18)
                .version(0L)
                .build();
    }

    private VillageNewsletterIssueEntity existingIssue() {
        return VillageNewsletterIssueEntity.builder()
                .villageId(VILLAGE_ID)
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(VillageNewsletterIssueStatus.FROZEN)
                .title("既存号")
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(LocalDateTime.of(2026, 6, 8, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 6, 15, 0, 0))
                .digestPostCount(0)
                .digestNewMemberCount(0)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .build();
    }
}
