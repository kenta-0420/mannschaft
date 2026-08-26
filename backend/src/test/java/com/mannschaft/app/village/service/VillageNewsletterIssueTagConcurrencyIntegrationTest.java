package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.controller.AbstractVillageIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
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
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 村ニュースレター号タグ付けの<b>並行編集（lost update 根治）</b>結合テスト（実 MySQL・Issue #2348 AC-1）。
 *
 * <h2>守る不変条件</h2>
 * <p>号へのタグ付け（{@code setIssueTags}）は中間表（issue_tags）のみを入れ替え、号行そのものは
 * 変更しない。素朴な実装では Hibernate が号行を dirty と見なさず {@code @Version} が上がらないため、
 * 「2 管理者が同一 version で同時にタグ付け」すると両方成功し、後勝ちで先の付け替えが失われる
 * （lost update）。号行に {@code OPTIMISTIC_FORCE_INCREMENT} ロックを掛ける根治により、
 * <b>一方が成功・他方が版競合（{@link VillageErrorCode#NEWSLETTER_ISSUE_VERSION_CONFLICT}）</b>で終わることを検証する。</p>
 *
 * <p>純 Mockito UT では InnoDB の版競合・ロック挙動を踏まないため、AC-1 は本 IT が唯一の実 DB 担保。
 * 金型は {@code ReservationGroupConcurrencyIntegrationTest}（{@code Executors}+{@code CountDownLatch}）。
 * Docker 未起動環境ではスキップされる。</p>
 */
@DisplayName("村ニュースレター号タグ付け 並行編集・lost update 根治 結合テスト（実MySQL・AC-1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageNewsletterIssueTagConcurrencyIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageNewsletterIssueService issueService;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @Autowired
    private VillageNewsletterIssueRepository issueRepository;

    private static final Long HEADMAN_A = 880101L;
    private static final Long HEADMAN_B = 880102L;

    @Test
    @DisplayName("AC-1: 2 管理者が同一 version で同時タグ付け — 一方 成功・他方 版競合(409)・最終 version は 1 増")
    void concurrentSetIssueTags_oneWinsOtherConflicts() throws Exception {
        // given: 生存村・2 名の現役 HEADMAN・version=0 の号
        VillageEntity village = persistVillage();
        UUID villageId = village.getId();
        persistHeadman(villageId, HEADMAN_A);
        persistHeadman(villageId, HEADMAN_B);
        VillageNewsletterIssueEntity issue = persistIssue(villageId);
        UUID issueId = issue.getId();
        Long baseVersion = issue.getVersion(); // = 0

        // when: 2 スレッドが同一 baseVersion で同時に setIssueTags（タグ空 = 中間表クリアのみ）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger versionConflict = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        Runnable taskA = () -> runSetTags(HEADMAN_A, villageId, issueId, baseVersion,
                ready, start, success, versionConflict, unexpected);
        Runnable taskB = () -> runSetTags(HEADMAN_B, villageId, issueId, baseVersion,
                ready, start, success, versionConflict, unexpected);
        pool.submit(taskA);
        pool.submit(taskB);

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("全スレッド完了").isTrue();

        // then: 未変換例外（500 相当）ゼロ・一方成功・他方版競合
        assertThat(unexpected)
                .as("BusinessException 以外が漏れないこと（握り潰し・500化の検出）: " + unexpected)
                .isEmpty();
        assertThat(success.get()).as("成功はちょうど 1 件").isEqualTo(1);
        assertThat(versionConflict.get()).as("敗者は版競合 1 件").isEqualTo(1);

        // then: 号の最終 version は base+1（両者成功＝lost update なら +0 or 不定になる）
        Long finalVersion = issueRepository.findById(issueId).orElseThrow().getVersion();
        assertThat(finalVersion).as("強制インクリメントで version がちょうど 1 上がる").isEqualTo(baseVersion + 1);
    }

    private void runSetTags(Long userId, UUID villageId, UUID issueId, Long expectedVersion,
                            CountDownLatch ready, CountDownLatch start,
                            AtomicInteger success, AtomicInteger versionConflict,
                            ConcurrentLinkedQueue<Throwable> unexpected) {
        ready.countDown();
        try {
            start.await();
            issueService.setIssueTags(villageId, issueId, userId, List.of(), expectedVersion);
            success.incrementAndGet();
        } catch (BusinessException e) {
            if (e.getErrorCode() == VillageErrorCode.NEWSLETTER_ISSUE_VERSION_CONFLICT) {
                versionConflict.incrementAndGet();
            } else {
                unexpected.add(e);
            }
        } catch (Throwable t) {
            unexpected.add(t);
        }
    }

    private VillageEntity persistVillage() {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("nl-conc-" + UUID.randomUUID().toString().substring(0, 8))
                .name("並行タグ村" + System.nanoTime())
                .description("lost update テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_A)
                .build());
    }

    private void persistHeadman(UUID villageId, Long userId) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now())
                .version(0L)
                .build());
    }

    private VillageNewsletterIssueEntity persistIssue(UUID villageId) {
        return issueRepository.saveAndFlush(VillageNewsletterIssueEntity.builder()
                .villageId(villageId)
                .frequency(VillageNewsletterFrequency.MONTHLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(VillageNewsletterIssueStatus.FROZEN)
                .title("並行タグ号")
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(LocalDateTime.of(2026, 6, 1, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 7, 1, 0, 0))
                .digestPostCount(10)
                .digestNewMemberCount(2)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .version(0L)
                .build());
    }
}
