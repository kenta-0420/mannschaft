package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.dto.CreateScheduleCommentRequest;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.service.ScheduleCommentService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.16 是正2【P1】: {@code reply_count} の原子的 UPDATE 化（読み取り→加算→書き込みのレース是正）の
 * 実証テスト。
 *
 * <h2>是正前の欠陥</h2>
 * <p>{@code ScheduleCommentService#createComment} は
 * {@code parent.incrementReplyCount()} → {@code save()} という「読み取り→加算→書き込み」だった。
 * 同じ親へ同時に返信されると両者が同じ値を読み、片方の加算が消える（ロストアップデート）。
 * 削除側（{@code deleteComment}）も同型で、こちらは原子的な {@code GREATEST(reply_count-1,0)} 版に
 * 是正した。</p>
 *
 * <h2>本テストの検証方針</h2>
 * <p>本クラスは class レベル {@code @Transactional} を<b>付けない</b>（付けると全スレッドが
 * テストの外側トランザクションへ相乗りし、真の並行コミットを再現できない）。各スレッドが
 * {@code ScheduleCommentService#createComment}（内部で独立した {@code @Transactional} 境界を持つ）を
 * 直接呼び、実際に MySQL へ並行コミットさせる。是正後の {@code reply_count} が「実際に生存する
 * 返信数」と一致することを実測する（is2 の受け入れ条件）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 是正2 reply_count の原子的 UPDATE 化（並行返信でロストアップデートが起きない）")
class ScheduleCommentReplyCountConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final int CONCURRENT_REPLIES = 20;

    @Autowired
    private ScheduleCommentService scheduleCommentService;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long scheduleId;
    private List<Long> replierIds;
    private UUID parentCommentId;

    @BeforeEach
    void setUp() {
        // 本クラスは class レベル @Transactional を意図的に付けない（Javadoc 参照）ため、
        // フィクスチャ投入だけは TransactionTemplate で明示的に 1 トランザクションへ包んでコミットする
        // （ScheduleCommentNotificationIsolationContractIT と同型）。
        transactionTemplate.executeWithoutResult(tx -> {
            String nonce = String.valueOf(System.nanoTime());
            teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 是正2並行", "sccc-team-" + nonce);
            Long authorId = ScheduleCommentTestFixtures.insertUser(em, "sccc-author-" + nonce + "@example.com", "起稿 太郎");
            MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            replierIds = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REPLIES; i++) {
                Long replierId = ScheduleCommentTestFixtures.insertUser(
                        em, "sccc-replier-" + nonce + "-" + i + "@example.com", "返信 " + i);
                MembershipTestHelper.insertMembership(em, replierId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                replierIds.add(replierId);
            }
            em.flush();

            scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .teamId(teamId)
                    .title("F0316 是正2並行返信予定")
                    .startAt(LocalDateTime.of(2026, 9, 10, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .commentsEnabled(true)
                    .attendanceRequired(true)
                    .allowProxyAttendance(true)
                    .isProxyAutoAccept(false)
                    .createdBy(authorId)
                    .build()).getId();
            em.flush();

            parentCommentId = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                    .scheduleId(scheduleId)
                    .userId(authorId)
                    .body("並行返信テスト対象の親コメント")
                    .build()).getId();
            em.flush();
        });
        em.clear();
    }

    @Test
    @DisplayName("是正2 同じ親へ20人が同時に返信しても reply_count が実際の生存返信数と一致する")
    void concurrentReplies_replyCountMatchesActualLiveReplies() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REPLIES);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REPLIES);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REPLIES);
        AtomicInteger failures = new AtomicInteger(0);

        for (Long replierId : replierIds) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    CreateScheduleCommentRequest request = new CreateScheduleCommentRequest();
                    request.setBody("並行返信 by " + replierId);
                    request.setParentId(parentCommentId.toString());
                    scheduleCommentService.createComment(scheduleId, replierId, request);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("全スレッドが準備完了すること").isTrue();
        start.countDown(); // 一斉に createComment を撃つ（レース窓を最大化する）
        assertThat(done.await(30, TimeUnit.SECONDS)).as("全スレッドが完了すること").isTrue();
        pool.shutdown();

        assertThat(failures.get()).as("並行投稿自体は失敗しない").isZero();

        // 新しい EntityManager 相当（fresh find）で DB の最新値を読む。
        em.clear();
        ScheduleCommentEntity parent = scheduleCommentRepository.findByIdAndScheduleId(parentCommentId, scheduleId)
                .orElseThrow();

        long actualLiveReplies = em.createQuery(
                        "SELECT COUNT(c) FROM ScheduleCommentEntity c "
                                + "WHERE c.rootId = :rootId AND c.deletedAt IS NULL",
                        Long.class)
                .setParameter("rootId", parentCommentId)
                .getSingleResult();

        assertThat(actualLiveReplies)
                .as("実際に生存している返信の行数")
                .isEqualTo(CONCURRENT_REPLIES);
        assertThat(parent.getReplyCount())
                .as("reply_count は読み取り→加算→書き込みだと同時実行で加算が失われ %d 未満になりうる。"
                        + "原子的 UPDATE（ScheduleCommentRepository#incrementReplyCount）なら実返信数と一致する",
                        CONCURRENT_REPLIES)
                .isEqualTo((int) actualLiveReplies);
    }
}
