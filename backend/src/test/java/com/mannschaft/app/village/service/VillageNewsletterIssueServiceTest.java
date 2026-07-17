package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageNewsletterIssueService} 単体テスト（F17.1 ②-2・設計書 §11.1）。
 *
 * <p>DB 無し・Mockito。集計器が返した snapshot を号へ複写し「集計 → 凍結」する流れと、
 * 冪等・改ざん不可の受け入れ条件を検証する。実 MySQL を通した期間集計の正当性は
 * {@code VillageNewsletterAggregateIntegrationTest} で別途検証する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-01: 集計・凍結後、{@code status=FROZEN} の号が生成され digest_post_count が投稿数と一致</li>
 *   <li>AC-02: 凍結済み号の digest_* は更新経路が存在しない（setter 無し）＋ freeze 二重遷移は
 *       {@code NEWSLETTER_ISSUE_ALREADY_FROZEN} に翻訳される＝改ざん不可</li>
 *   <li>AC-03: 同一村×頻度×期間で二度呼んでも save/aggregate せず既存号を返す（冪等）</li>
 *   <li>AC-05: 祭/寄合/募集の件数が digest_festival/meetup/recruit_count に反映される</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNewsletterIssueService 単体テスト（F17.1 ②-2）")
class VillageNewsletterIssueServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b01");
    private static final UUID NEWSLETTER_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b02");
    private static final VillageNewsletterFrequency FREQ = VillageNewsletterFrequency.WEEKLY;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 8, 0, 0);
    private static final LocalDateTime SCHEDULED_PUBLISH_AT = LocalDateTime.of(2026, 6, 12, 18, 0);

    @Mock
    private VillageNewsletterIssueRepository issueRepository;
    @Mock
    private VillageNewsletterDigestAggregator aggregator;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VillageNewsletterIssueService service;

    // ========================================================================
    // AC-01 / AC-05 — 集計 → 凍結
    // ========================================================================

    @Test
    @DisplayName("AC-01/05: 集計器の snapshot を号へ複写し FROZEN 化。post/festival/meetup/recruit が一致")
    void aggregateAndFreeze_copiesSnapshotAndFreezes() {
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.empty());
        // postCount=20（掲示板12+タイムライン8想定）, newMember=3, festival=2, meetup=1, recruit=4
        given(aggregator.aggregate(eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(new NewsletterDigestSnapshot(20, 3, 2, 1, 4,
                        List.of(Map.entry("夏祭り", 3), Map.entry("清掃", 2))));
        given(issueRepository.save(any(VillageNewsletterIssueEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        VillageNewsletterIssueEntity result = service.aggregateAndFreeze(
                VILLAGE_ID, FREQ, NEWSLETTER_ID, PERIOD_START, PERIOD_END, SCHEDULED_PUBLISH_AT);

        // AC-01: FROZEN・投稿数一致
        assertThat(result.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        assertThat(result.getDigestPostCount()).isEqualTo(20);
        assertThat(result.getDigestNewMemberCount()).isEqualTo(3);
        // AC-05: 祭/寄合/募集
        assertThat(result.getDigestFestivalCount()).isEqualTo(2);
        assertThat(result.getDigestMeetupCount()).isEqualTo(1);
        assertThat(result.getDigestRecruitCount()).isEqualTo(4);
        // TOP3 トピックが digest_topic_* に載る
        assertThat(result.getDigestTopic1Name()).isEqualTo("夏祭り");
        assertThat(result.getDigestTopic1Count()).isEqualTo(3);
        assertThat(result.getDigestTopic2Name()).isEqualTo("清掃");
        assertThat(result.getDigestTopic2Count()).isEqualTo(2);
        assertThat(result.getDigestTopic3Name()).isNull();
        assertThat(result.getDigestTopic3Count()).isEqualTo(0);
        // 期間・配信予定・種別
        assertThat(result.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(result.getPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(result.getScheduledPublishAt()).isEqualTo(SCHEDULED_PUBLISH_AT);
        assertThat(result.getIssueType()).isEqualTo(VillageNewsletterIssueType.REGULAR);
        assertThat(result.getVisibility()).isEqualTo(VillageNewsletterVisibility.VILLAGE_MEMBERS);

        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_NEWSLETTER_ISSUE_FROZEN.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ========================================================================
    // AC-03 — 冪等（既存号があれば何もしない）
    // ========================================================================

    @Test
    @DisplayName("AC-03: 既存号があれば集計も保存もせず同一号を返す（冪等・二重起動対策）")
    void aggregateAndFreeze_idempotentWhenIssueExists() {
        VillageNewsletterIssueEntity existing = issueWithStatus(VillageNewsletterIssueStatus.FROZEN);
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.of(existing));

        VillageNewsletterIssueEntity result = service.aggregateAndFreeze(
                VILLAGE_ID, FREQ, NEWSLETTER_ID, PERIOD_START, PERIOD_END, SCHEDULED_PUBLISH_AT);

        assertThat(result).isSameAs(existing);
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(issueRepository, never()).save(any());
        verify(auditLogService, never())
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ========================================================================
    // AC-02 — 改ざん不可
    // ========================================================================

    @Test
    @DisplayName("AC-02: 既存 FROZEN 号の集計値は再集計・上書きされない（frozen snapshot は不変）")
    void aggregateAndFreeze_frozenDigestIsImmutable() {
        // 既存の凍結号。digest_* は setter を持たないため、そもそも更新経路が型として存在しない
        // （＝コンパイル時に改ざん不可が保証される。ここではサービス経由の再集計も起きないことを確認）。
        VillageNewsletterIssueEntity existing = issueWithStatus(VillageNewsletterIssueStatus.FROZEN);
        int frozenPostCount = existing.getDigestPostCount();
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.of(existing));

        VillageNewsletterIssueEntity result = service.aggregateAndFreeze(
                VILLAGE_ID, FREQ, NEWSLETTER_ID, PERIOD_START, PERIOD_END, SCHEDULED_PUBLISH_AT);

        // 集計器を呼ばない＝凍結済みの値が再計算・上書きされない
        verify(aggregator, never()).aggregate(any(), any(), any());
        assertThat(result.getDigestPostCount()).isEqualTo(frozenPostCount);
        assertThat(result.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
    }

    @Test
    @DisplayName("AC-02: AGGREGATED 以外からの凍結遷移は NEWSLETTER_ISSUE_ALREADY_FROZEN に翻訳される")
    void aggregateAndFreeze_translatesIllegalFreezeToDomainError() {
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.empty());
        given(aggregator.aggregate(eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(new NewsletterDigestSnapshot(1, 0, 0, 0, 0, List.of()));
        // save が「既に凍結済み」の号を返す異常系（レース・二重処理）を模す → freeze() が IllegalStateException
        given(issueRepository.save(any(VillageNewsletterIssueEntity.class)))
                .willReturn(issueWithStatus(VillageNewsletterIssueStatus.FROZEN));

        assertThatThrownBy(() -> service.aggregateAndFreeze(
                VILLAGE_ID, FREQ, NEWSLETTER_ID, PERIOD_START, PERIOD_END, SCHEDULED_PUBLISH_AT))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NEWSLETTER_ISSUE_ALREADY_FROZEN);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageNewsletterIssueEntity issueWithStatus(VillageNewsletterIssueStatus status) {
        return VillageNewsletterIssueEntity.builder()
                .villageId(VILLAGE_ID)
                .newsletterId(NEWSLETTER_ID)
                .frequency(FREQ)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(status)
                .title("既存号")
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .digestPostCount(7)
                .digestNewMemberCount(1)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .build();
    }
}
