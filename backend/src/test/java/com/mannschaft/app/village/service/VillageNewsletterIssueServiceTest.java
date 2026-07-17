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
 * <p>DB 無し・Mockito。<b>集計は本サービスの外で済ませ</b>、確定済み {@link NewsletterDigestSnapshot}
 * を号へ複写して凍結する流れと、冪等・改ざん不可の受け入れ条件を検証する。集計器（越境読み取り）は
 * バッチがトランザクション外で呼ぶため、本サービスは集計器に依存しない（番人 D-3 回避）。
 * 実 MySQL を通した期間集計の正当性は {@code VillageNewsletterAggregateIntegrationTest} で別途検証する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-01: 凍結後、{@code status=FROZEN} の号が生成され digest_post_count が snapshot の投稿数と一致</li>
 *   <li>AC-02: 凍結済み号の digest_* は更新経路が存在しない（setter 無し）＋ freeze 二重遷移は
 *       {@code NEWSLETTER_ISSUE_ALREADY_FROZEN} に翻訳される＝改ざん不可</li>
 *   <li>AC-03: 既存号があれば save せず既存号を返す（冪等・最終防衛）</li>
 *   <li>AC-05: snapshot の祭/寄合/募集件数が digest_festival/meetup/recruit_count に載る</li>
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
    private AuditLogService auditLogService;

    @InjectMocks
    private VillageNewsletterIssueService service;

    private VillageNewsletterIssueEntity freezeIssue(NewsletterDigestSnapshot snapshot) {
        return service.freezeIssue(
                VILLAGE_ID, FREQ, NEWSLETTER_ID, PERIOD_START, PERIOD_END, SCHEDULED_PUBLISH_AT, snapshot);
    }

    // ========================================================================
    // AC-01 / AC-05 — snapshot を号へ複写して凍結
    // ========================================================================

    @Test
    @DisplayName("AC-01/05: snapshot を号へ複写し FROZEN 化。post/festival/meetup/recruit が一致")
    void freezeIssue_copiesSnapshotAndFreezes() {
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.empty());
        given(issueRepository.save(any(VillageNewsletterIssueEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // postCount=20（掲示板12+タイムライン8想定）, newMember=3, festival=2, meetup=1, recruit=4
        VillageNewsletterIssueEntity result = freezeIssue(
                new NewsletterDigestSnapshot(20, 3, 2, 1, 4,
                        List.of(Map.entry("夏祭り", 3), Map.entry("清掃", 2))));

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
    @DisplayName("AC-03: 既存号があれば保存せず同一号を返す（冪等・並行実行の最終防衛）")
    void freezeIssue_idempotentWhenIssueExists() {
        VillageNewsletterIssueEntity existing = issueWithStatus(VillageNewsletterIssueStatus.FROZEN);
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.of(existing));

        VillageNewsletterIssueEntity result = freezeIssue(
                new NewsletterDigestSnapshot(99, 99, 99, 99, 99, List.of()));

        assertThat(result).isSameAs(existing);
        verify(issueRepository, never()).save(any());
        verify(auditLogService, never())
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ========================================================================
    // AC-02 — 改ざん不可
    // ========================================================================

    @Test
    @DisplayName("AC-02: 既存 FROZEN 号の集計値は上書きされない（引数 snapshot を無視して既存号を返す）")
    void freezeIssue_frozenDigestIsImmutable() {
        // 既存の凍結号。digest_* は setter を持たないため、そもそも更新経路が型として存在しない
        // （＝コンパイル時に改ざん不可が保証される）。ここではサービス経由でも上書きされないことを確認。
        VillageNewsletterIssueEntity existing = issueWithStatus(VillageNewsletterIssueStatus.FROZEN);
        int frozenPostCount = existing.getDigestPostCount();
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.of(existing));

        // 新しい snapshot（post=999）を渡しても、既存号の値は書き換わらない。
        VillageNewsletterIssueEntity result = freezeIssue(
                new NewsletterDigestSnapshot(999, 0, 0, 0, 0, List.of()));

        assertThat(result.getDigestPostCount()).isEqualTo(frozenPostCount);
        assertThat(result.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        verify(issueRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-02: AGGREGATED 以外からの凍結遷移は NEWSLETTER_ISSUE_ALREADY_FROZEN に翻訳される")
    void freezeIssue_translatesIllegalFreezeToDomainError() {
        given(issueRepository.findByVillageIdAndFrequencyAndPeriodStart(VILLAGE_ID, FREQ, PERIOD_START))
                .willReturn(Optional.empty());
        // save が「既に凍結済み」の号を返す異常系（レース・二重処理）を模す → freeze() が IllegalStateException
        given(issueRepository.save(any(VillageNewsletterIssueEntity.class)))
                .willReturn(issueWithStatus(VillageNewsletterIssueStatus.FROZEN));

        assertThatThrownBy(() -> freezeIssue(new NewsletterDigestSnapshot(1, 0, 0, 0, 0, List.of())))
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
