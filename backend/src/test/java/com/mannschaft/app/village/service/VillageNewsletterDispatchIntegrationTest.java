package com.mannschaft.app.village.service;

import com.mannschaft.app.village.batch.VillageNewsletterDispatchBatchService;
import com.mannschaft.app.village.controller.AbstractVillageIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.VillageNewsletterSendLogEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.repository.VillageNewsletterSendLogRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17.1 ②-3 — 村ニュースレター配信の統合テスト（実 MySQL Testcontainers・設計書 §11.3）。
 *
 * <p>Service〜Repository〜DB を実 Bean で通し、号駆動の配信で <b>号が PUBLISHED 化</b>し、
 * <b>送信ログが issue_id 付きで 1 件</b>残り、<b>opt-out 済みユーザーが受信者数から除外</b>される
 * ことを検証する。配信予定が未来の号は選ばれないこと（選択 SQL の正当性）も併せて担保する。</p>
 *
 * <h2>日時フィクスチャの流儀</h2>
 * <p>日時は文字列リテラルではなく {@link LocalDateTime} を bind する（JST/UTC 境界の 9 時間ズレ回避・
 * memory {@code feedback_it_fixture_datetime_tz_bind}）。号エンティティの {@code @PrePersist} は
 * {@code createdAt} を {@code if null} ガードで扱い、{@code status} は明示指定した FROZEN を保持する。</p>
 *
 * <h2>受け入れ条件との対応</h2>
 * <ul>
 *   <li>AC-10: 配信期到来の FROZEN 号が自動配信され PUBLISHED 化・send_log が issue_id 付きで 1 件</li>
 *   <li>AC-12: opt-out 済みユーザーが受信者数（recipient_count）から除外される</li>
 * </ul>
 */
@DisplayName("F17.1 ②-3 村ニュースレター配信 統合テスト")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageNewsletterDispatchIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageNewsletterDispatchBatchService batchService;
    @Autowired
    private VillageNewsletterIssueRepository issueRepository;
    @Autowired
    private VillageNewsletterSendLogRepository sendLogRepository;
    @Autowired
    private VillageNewsletterOptOutRepository optOutRepository;
    @Autowired
    private VillageNewsletterRepository newsletterRepository;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 8, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 15, 0, 0);
    private static final LocalDateTime SCHEDULED_PAST = LocalDateTime.of(2026, 6, 19, 18, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 20, 0, 0);

    private UUID villageId;

    // ========================================================================
    // AC-10 / AC-12 — 配信期到来 FROZEN 号を配信・PUBLISHED 化し、opt-out を除外
    // ========================================================================

    @Test
    @DisplayName("AC-10/12: 配信期到来の FROZEN 号を配信 → PUBLISHED・send_log(issue_id 付き)1 件・opt-out 除外")
    void dispatchForDate_publishesFrozenIssue_andExcludesOptedOut() {
        villageId = persistVillage().getId();
        UUID newsletterId = persistNewsletter(villageId).getId();
        // 現役 USER メンバー 3 名
        persistMembership(2001L);
        persistMembership(2002L);
        persistMembership(2003L);
        // 2002 は opt-out（受信者から除外されるべき）
        persistOptOut(2002L);
        // 配信期が過ぎた FROZEN 号
        VillageNewsletterIssueEntity issue = persistFrozenIssue(villageId, newsletterId, SCHEDULED_PAST, 5, null);

        int published = batchService.dispatchForDate(NOW);
        assertThat(published).isEqualTo(1);

        // 号は PUBLISHED 化
        VillageNewsletterIssueEntity reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VillageNewsletterIssueStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();

        // send_log が issue_id 付きで 1 件・受信者数は opt-out 除外後の 2
        List<VillageNewsletterSendLogEntity> logs =
                sendLogRepository.findByNewsletterIdOrderBySentAtDesc(newsletterId);
        assertThat(logs).hasSize(1);
        VillageNewsletterSendLogEntity log = logs.get(0);
        assertThat(log.getIssueId()).isEqualTo(issue.getId());
        assertThat(log.getRecipientCount()).isEqualTo(2); // 3 メンバー − 1 opt-out
        assertThat(log.getSuccessCount() + log.getFailureCount()).isEqualTo(2);
    }

    // ========================================================================
    // 選択 SQL の正当性 — 配信予定が未来の号は配信されない
    // ========================================================================

    @Test
    @DisplayName("配信予定が未来の FROZEN 号は配信対象に選ばれない（scheduled_publish_at > now）")
    void dispatchForDate_futureScheduledIssue_notDispatched() {
        villageId = persistVillage().getId();
        UUID newsletterId = persistNewsletter(villageId).getId();
        persistMembership(3001L);
        LocalDateTime future = NOW.plusDays(7);
        VillageNewsletterIssueEntity issue = persistFrozenIssue(villageId, newsletterId, future, 1, null);

        int published = batchService.dispatchForDate(NOW);
        assertThat(published).isEqualTo(0);

        VillageNewsletterIssueEntity reloaded = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        assertThat(sendLogRepository.findByNewsletterIdOrderBySentAtDesc(newsletterId)).isEmpty();
    }

    // ========================================================================
    // フィクスチャ
    // ========================================================================

    private VillageEntity persistVillage() {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("nl-dsp-" + UUID.randomUUID().toString().substring(0, 8))
                .name("配信テスト村" + System.nanoTime())
                .description("ニュースレター配信テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(101L)
                .build());
    }

    private VillageNewsletterEntity persistNewsletter(UUID vId) {
        return newsletterRepository.saveAndFlush(VillageNewsletterEntity.builder()
                .villageId(vId)
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .isEnabled(true)
                .aggregateDay(1)
                .dispatchDay(5)
                .dispatchHour(18)
                .version(0L)
                .build());
    }

    private void persistMembership(Long userId) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(PERIOD_START)
                .version(0L)
                .build());
    }

    private void persistOptOut(Long userId) {
        optOutRepository.saveAndFlush(VillageNewsletterOptOutEntity.builder()
                .villageId(villageId)
                .userId(userId)
                .optedOutAt(PERIOD_START)
                .build());
    }

    private VillageNewsletterIssueEntity persistFrozenIssue(
            UUID vId, UUID newsletterId, LocalDateTime scheduledPublishAt, int postCount, String comment) {
        return issueRepository.saveAndFlush(VillageNewsletterIssueEntity.builder()
                .villageId(vId)
                .newsletterId(newsletterId)
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(VillageNewsletterIssueStatus.FROZEN)
                .title("2026年06月08日週 村だより")
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .aggregatedAt(PERIOD_END)
                .scheduledPublishAt(scheduledPublishAt)
                .digestPostCount(postCount)
                .digestNewMemberCount(0)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .headmanComment(comment)
                .version(0L)
                .build());
    }
}
