package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.batch.VillageNewsletterAggregateBatchService;
import com.mannschaft.app.village.controller.AbstractVillageIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17.1 ②-2 — 村ニュースレター集計・凍結の統合テスト（実 MySQL Testcontainers・設計書 §11.1）。
 *
 * <p>Service〜Repository〜DB を実 Bean で通し、期間内のみが数えられること（半開区間・論理削除除外）と、
 * 二度実行しても号が 1 件で収束すること（冪等）を検証する。モック UT では実 SQL の半開区間・
 * {@code deleted_at} 述語が欠けても偽 green になるため、期間集計の正当性は本 IT が担保する。</p>
 *
 * <h2>日時フィクスチャの流儀（重要）</h2>
 * <p>日時は文字列リテラルではなく {@link LocalDateTime} を bind する（JST/UTC 境界の 9 時間ズレ回避・
 * memory {@code feedback_it_fixture_datetime_tz_bind}）。掲示板/タイムラインは {@link BaseEntity} の
 * {@code @PrePersist} が {@code createdAt} を <b>無条件で now() に上書き</b>するため、
 * insert 後に reflection で {@code createdAt} を是正して再 flush する（祭/寄合/募集/メンバーは
 * {@code @PrePersist} が {@code if null} ガード付きのため builder で直接制御できる）。</p>
 *
 * <h2>受け入れ条件との対応</h2>
 * <ul>
 *   <li>AC-01: 集計後 {@code status=FROZEN} の号が生成され digest_post_count = 期間内掲示板 + タイムライン</li>
 *   <li>AC-03: 同一村×頻度×期間で二度集計しても号は 1 件（冪等）</li>
 *   <li>AC-05: 期間内の祭/寄合/募集件数が digest_festival/meetup/recruit_count に反映（論理削除除外）</li>
 * </ul>
 */
@DisplayName("F17.1 ②-2 村ニュースレター集計・凍結 統合テスト")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageNewsletterAggregateIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageNewsletterAggregateBatchService batchService;
    @Autowired
    private VillageNewsletterIssueRepository issueRepository;
    @Autowired
    private VillageNewsletterRepository newsletterRepository;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @Autowired
    private VillageFestivalRepository festivalRepository;
    @Autowired
    private VillageMeetupRepository meetupRepository;
    @Autowired
    private VillageMatchRecruitRepository matchRecruitRepository;
    @Autowired
    private BulletinThreadRepository bulletinThreadRepository;
    @Autowired
    private TimelinePostRepository timelinePostRepository;

    // 集計対象期間 [2026-06-01, 2026-07-01)
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final LocalDateTime IN_PERIOD = LocalDateTime.of(2026, 6, 15, 10, 0);
    private static final LocalDateTime BEFORE_PERIOD = LocalDateTime.of(2026, 5, 20, 10, 0);
    private static final LocalDateTime AFTER_PERIOD = LocalDateTime.of(2026, 7, 5, 10, 0);

    /**
     * 集計基準日。バッチはこの日を「今日」として periodEnd = today 0:00 = {@link #PERIOD_END}、
     * 直近号が無いので periodStart = periodEnd.minusMonths(1) = {@link #PERIOD_START} を算出する。
     * MONTHLY の集計日は日付一致（{@code 2026-07-01} の DOM=1）で当日を集計日にする。
     */
    private static final LocalDate AGG_TODAY = LocalDate.of(2026, 7, 1);

    private UUID villageId;

    // ========================================================================
    // AC-01 / AC-05 — バッチ経由の期間集計と凍結（越境読み取りは取引外）
    // ========================================================================

    @Test
    @DisplayName("AC-01/05: バッチ集計で期間内のみが digest に数えられ FROZEN 号が生成される（半開区間・論理削除除外）")
    void aggregateForDate_countsOnlyWithinPeriod() {
        villageId = persistVillage().getId();
        // MONTHLY・当日(2026-07-01)を集計日にする設定
        persistNewsletter(villageId, VillageNewsletterFrequency.MONTHLY,
                AGG_TODAY.getDayOfMonth(), 1, 18);

        // 掲示板: 期間内 2 件 + 期間外 1 件（前）
        persistBulletinThread("夏祭りの準備", IN_PERIOD, false);
        persistBulletinThread("夏祭りの当日", IN_PERIOD, false);
        persistBulletinThread("期間外スレ", BEFORE_PERIOD, false);
        // タイムライン: 期間内 3 件 + 期間外 1 件（後）
        persistTimelinePost("投稿A", IN_PERIOD);
        persistTimelinePost("投稿B", IN_PERIOD);
        persistTimelinePost("投稿C", IN_PERIOD);
        persistTimelinePost("期間外投稿", AFTER_PERIOD);
        // 新メンバー: 期間内 2 + 期間外 1
        persistMembership(1001L, IN_PERIOD);
        persistMembership(1002L, IN_PERIOD);
        persistMembership(1003L, BEFORE_PERIOD);
        // 祭: 期間内 2 + 期間外 1
        persistFestival(IN_PERIOD, false);
        persistFestival(IN_PERIOD, false);
        persistFestival(BEFORE_PERIOD, false);
        // 寄合: 期間内 1
        persistMeetup(IN_PERIOD);
        // 募集: 期間内 3 + 期間内だが論理削除 1（除外されること）
        persistRecruit(IN_PERIOD, false);
        persistRecruit(IN_PERIOD, false);
        persistRecruit(IN_PERIOD, false);
        persistRecruit(IN_PERIOD, true);

        int aggregated = batchService.aggregateForDate(AGG_TODAY);
        assertThat(aggregated).isEqualTo(1);

        VillageNewsletterIssueEntity issue = issueRepository
                .findByVillageIdAndFrequencyAndPeriodStart(
                        villageId, VillageNewsletterFrequency.MONTHLY, PERIOD_START)
                .orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        assertThat(issue.getPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(issue.getDigestPostCount()).isEqualTo(5);       // 掲示板 2 + タイムライン 3
        assertThat(issue.getDigestNewMemberCount()).isEqualTo(2);
        assertThat(issue.getDigestFestivalCount()).isEqualTo(2);
        assertThat(issue.getDigestMeetupCount()).isEqualTo(1);
        assertThat(issue.getDigestRecruitCount()).isEqualTo(3);    // 論理削除 1 は除外
    }

    // ========================================================================
    // AC-03 — 冪等（二度集計しても号は 1 件）
    // ========================================================================

    @Test
    @DisplayName("AC-03: バッチを二度実行しても号は 1 件（冪等）")
    void aggregateForDate_isIdempotent() {
        villageId = persistVillage().getId();
        persistNewsletter(villageId, VillageNewsletterFrequency.MONTHLY,
                AGG_TODAY.getDayOfMonth(), 1, 18);
        persistBulletinThread("スレ", IN_PERIOD, false);

        batchService.aggregateForDate(AGG_TODAY);
        batchService.aggregateForDate(AGG_TODAY);

        long total = issueRepository
                .findByVillageIdAndDeletedAtIsNullOrderByCreatedAtDesc(villageId, PageRequest.of(0, 10))
                .getTotalElements();
        assertThat(total).isEqualTo(1);
    }

    // ========================================================================
    // バッチ aggregateForDate — 集計日一致で号を生成し二度目は冪等
    // ========================================================================

    @Test
    @DisplayName("バッチ: 当日が集計日の WEEKLY 設定を集計・凍結し、二度目も号は 1 件（冪等）")
    void aggregateForDate_weeklyOnAggregateDay() {
        villageId = persistVillage().getId();
        LocalDate today = LocalDate.of(2026, 6, 15);
        int aggregateDayOfWeek = today.getDayOfWeek().getValue(); // 月=1 … 日=7
        persistNewsletter(villageId, VillageNewsletterFrequency.WEEKLY, aggregateDayOfWeek, 5, 18);

        int aggregated = batchService.aggregateForDate(today);

        assertThat(aggregated).isEqualTo(1);
        LocalDateTime periodEnd = today.atStartOfDay();
        LocalDateTime periodStart = periodEnd.minusWeeks(1); // 直近号が無いので既定で 1 週前
        Optional<VillageNewsletterIssueEntity> issue = issueRepository
                .findByVillageIdAndFrequencyAndPeriodStart(
                        villageId, VillageNewsletterFrequency.WEEKLY, periodStart);
        assertThat(issue).isPresent();
        assertThat(issue.get().getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        assertThat(issue.get().getPeriodEnd()).isEqualTo(periodEnd);
        // 配信予定 = today 以降で最初の金曜(5) の 18:00
        assertThat(issue.get().getScheduledPublishAt().getHour()).isEqualTo(18);

        // 二度目: 冪等で号は増えない
        batchService.aggregateForDate(today);
        long total = issueRepository
                .findByVillageIdAndDeletedAtIsNullOrderByCreatedAtDesc(villageId, PageRequest.of(0, 10))
                .getTotalElements();
        assertThat(total).isEqualTo(1);
    }

    // ========================================================================
    // フィクスチャ
    // ========================================================================

    private VillageEntity persistVillage() {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("nl-agg-" + UUID.randomUUID().toString().substring(0, 8))
                .name("集計テスト村" + System.nanoTime())
                .description("ニュースレター集計テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(101L)
                .build());
    }

    /** 掲示板スレッドを永続化する（createdAt は @PrePersist 上書き後に reflection で是正）。 */
    private void persistBulletinThread(String title, LocalDateTime createdAt, boolean deleted) {
        BulletinThreadEntity t = bulletinThreadRepository.save(BulletinThreadEntity.builder()
                .scopeType(ScopeType.PERSONAL) // ダミー。クエリは scopeVillageId で絞る
                .scopeId(0L)
                .scopeVillageId(villageId)
                .title(title)
                .body("本文")
                .build());
        forceCreatedAt(t, createdAt);
        if (deleted) {
            t.softDelete();
        }
        bulletinThreadRepository.saveAndFlush(t);
    }

    /** タイムライン投稿を永続化する（PUBLISHED・根投稿・createdAt を reflection で是正）。 */
    private void persistTimelinePost(String content, LocalDateTime createdAt) {
        TimelinePostEntity p = timelinePostRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.PERSONAL) // ダミー。クエリは scopeVillageId で絞る
                .scopeId(0L)
                .scopeVillageId(villageId)
                .userId(101L)
                .content(content)
                .status(PostStatus.PUBLISHED)
                .build());
        forceCreatedAt(p, createdAt);
        timelinePostRepository.saveAndFlush(p);
    }

    private void persistMembership(Long userId, LocalDateTime joinedAt) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(joinedAt)
                .version(0L)
                .build());
    }

    private void persistFestival(LocalDateTime createdAt, boolean deleted) {
        festivalRepository.saveAndFlush(VillageFestivalEntity.builder()
                .villageId(villageId)
                .title("祭")
                .description("説明")
                .startsAt(createdAt.plusDays(1))
                .endsAt(createdAt.plusDays(2))
                .status(VillageFestivalStatus.SCHEDULED)
                .createdByUserId(101L)
                .createdAt(createdAt) // onCreate は if null ガードのため保持される
                .updatedAt(createdAt)
                .deletedAt(deleted ? createdAt : null)
                .version(0L)
                .build());
    }

    private void persistMeetup(LocalDateTime createdAt) {
        meetupRepository.saveAndFlush(VillageMeetupEntity.builder()
                .villageId(villageId)
                .title("寄合")
                .description("説明")
                .organizerUserId(101L)
                .status(VillageMeetupStatus.PLANNING)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .version(0L)
                .build());
    }

    private void persistRecruit(LocalDateTime createdAt, boolean deleted) {
        matchRecruitRepository.saveAndFlush(VillageMatchRecruitEntity.builder()
                .villageId(villageId)
                .postedByUserId(101L)
                .category(VillageMatchRecruitCategory.PRACTICE_MATCH)
                .title("募集")
                .description("説明")
                .status(VillageMatchRecruitStatus.OPEN)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .deletedAt(deleted ? createdAt : null)
                .version(0L)
                .build());
    }

    private void persistNewsletter(UUID vId, VillageNewsletterFrequency frequency,
                                   int aggregateDay, int dispatchDay, int dispatchHour) {
        newsletterRepository.saveAndFlush(VillageNewsletterEntity.builder()
                .villageId(vId)
                .frequency(frequency)
                .isEnabled(true)
                .aggregateDay(aggregateDay)
                .dispatchDay(dispatchDay)
                .dispatchHour(dispatchHour)
                .version(0L)
                .build());
    }

    /** {@link BaseEntity#createdAt} を reflection で上書きする（@PrePersist が now() に潰すため）。 */
    private void forceCreatedAt(Object entity, LocalDateTime ts) {
        try {
            Field f = BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(entity, ts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
