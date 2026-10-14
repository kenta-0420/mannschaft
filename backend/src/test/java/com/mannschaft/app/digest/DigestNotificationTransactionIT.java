package com.mannschaft.app.digest;

import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import com.mannschaft.app.digest.service.DigestAiProvider;
import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.CommitSaboteurConfig;
import com.mannschaft.app.support.test.CommitSaboteurConfig.CommitSaboteur;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2997 / CMP-260827-1152 G7-a（digest）— AI ダイジェスト生成の付随通知の業務TX切り離しの実DB検証。
 *
 * <p>対象: {@code DigestAsyncExecutor#generateAiDigestAsync}（AI 生成の完了／失敗通知）。</p>
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <ul>
 *   <li>executor 全体が 1 つの {@code @Transactional}（AI 呼び出しまで包含）で、通知
 *       ({@code NotificationHelper#notify}) がそのTXに参加する。通知の DB 例外が rollback-only を残すと
 *       GENERATED への遷移ごと巻き戻り、外側の catch が FAILED に落とす（AC-B red）。</li>
 *   <li>成功側に {@code triggeredBy == null} の検査が無く、null で NPE → 生成成功なのに FAILED（AC null red）。</li>
 * </ul>
 *
 * <h2>観測方法と限界</h2>
 * <p>{@link NotificationService} を spy し {@code createNotification} の呼び出しを観測する。AC-B は spy が入口で
 * {@link DataIntegrityViolationException} を投げる形の注入であり、実 MySQL 上の永続化失敗ではない。
 * 「通知が来ない」ことの確認は sleep ではなく、状態確定の待機の印と event-pool の drain を待ってから行う。
 * クラスに {@code @Transactional} を付けない（commit が起きず AFTER_COMMIT が発火しないため）。</p>
 */
@Import(CommitSaboteurConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G7-a digest 付随通知のTX分離（実DB）")
class DigestNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired private DigestAsyncExecutor executor;
    @Autowired private TimelineDigestRepository digestRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CommitSaboteur saboteur;
    @Autowired @Qualifier("event-pool") private java.util.concurrent.Executor eventPool;

    @MockitoBean private DigestAiProvider aiProvider;
    @MockitoSpyBean private NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    private record Fixture(Long teamId, Long userId) {
    }

    @AfterEach
    void tearDown() {
        saboteur.disarm();
    }

    @Test
    @DisplayName("AC-A2: 生成に成功したら GENERATED で確定し、作成者に DIGEST_COMPLETED の通知行が作られる")
    void 成功すると完了通知が作られる() {
        Fixture f = fixture("ok");
        Long digestId = insertGenerating(f, f.userId());
        given(aiProvider.generate(any(), any(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .willReturn(new DigestAiProvider.AiDigestResult("題", "本文", "抜粋", "m", 1, 2));

        executor.generateAiDigestAsync(digestId, "TEAM", f.teamId(), "NARRATIVE", null, true, true, false, "ja");

        awaitStatus(digestId, DigestStatus.GENERATED);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(f.userId(), "DIGEST_COMPLETED"))
                        .anyMatch(n -> digestId.equals(n.getSourceId())));
    }

    @Test
    @DisplayName("AC-A: 確定TXが commit 直前に rollback したら通知生成は一度も呼ばれない（注入点への到達も確認する）")
    void 確定TXがロールバックしたら通知は作られない() {
        Fixture f = fixture("rb");
        Long digestId = insertGenerating(f, f.userId());
        given(aiProvider.generate(any(), any(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .willReturn(new DigestAiProvider.AiDigestResult("題", "本文", "抜粋", "m", 1, 2));
        saboteur.arm("DigestGenerationNotificationEvent");

        executor.generateAiDigestAsync(digestId, "TEAM", f.teamId(), "NARRATIVE", null, true, true, false, "ja");

        // 注入点に到達した（= publish されたうえで commit 直前まで来た）ことを待つ。
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(saboteur.tripped()).as("BEFORE_COMMIT 注入点への到達").isPositive());
        drainEventPool();
        assertThat(createNotificationCalls()).as("rollback されたので通知生成は呼ばれていない").isZero();
        assertThat(notificationsOf(f.userId(), "DIGEST_COMPLETED")).isEmpty();
    }

    @Test
    @DisplayName("AC-B: 通知の永続化が失敗しても GENERATED のまま確定し、GENERATING に残らず FAILED にも落ちない")
    void 通知失敗でも生成結果は残る() {
        Fixture f = fixture("nf");
        Long digestId = insertGenerating(f, f.userId());
        given(aiProvider.generate(any(), any(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .willReturn(new DigestAiProvider.AiDigestResult("題", "本文", "抜粋", "m", 1, 2));
        failNotificationPersistence();

        executor.generateAiDigestAsync(digestId, "TEAM", f.teamId(), "NARRATIVE", null, true, true, false, "ja");

        // 配送が実際に試みられたこと（注入点到達）を待ってから状態を確かめる。
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(createNotificationCalls()).as("通知生成の注入点への到達").isPositive());
        drainEventPool();
        assertThat(statusOf(digestId)).isEqualTo(DigestStatus.GENERATED);
        assertThat(notificationsOf(f.userId(), "DIGEST_COMPLETED")).isEmpty();
    }

    @Test
    @DisplayName("生成に失敗したら FAILED で確定し、作成者に DIGEST_FAILED の通知行が作られる")
    void 生成失敗で失敗通知が作られる() {
        Fixture f = fixture("fail");
        Long digestId = insertGenerating(f, f.userId());
        given(aiProvider.generate(any(), any(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .willThrow(new IllegalStateException("AI 障害（検証用）"));

        executor.generateAiDigestAsync(digestId, "TEAM", f.teamId(), "NARRATIVE", null, true, true, false, "ja");

        awaitStatus(digestId, DigestStatus.FAILED);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(f.userId(), "DIGEST_FAILED"))
                        .anyMatch(n -> digestId.equals(n.getSourceId())));
    }

    @Test
    @DisplayName("triggeredBy が null のダイジェストは通知0件で GENERATED のまま確定する")
    void 起動者不明なら通知せず生成は成功する() {
        Fixture f = fixture("nul");
        Long digestId = insertGenerating(f, null);
        given(aiProvider.generate(any(), any(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .willReturn(new DigestAiProvider.AiDigestResult("題", "本文", "抜粋", "m", 1, 2));

        executor.generateAiDigestAsync(digestId, "TEAM", f.teamId(), "NARRATIVE", null, true, true, false, "ja");

        awaitStatus(digestId, DigestStatus.GENERATED);
        drainEventPool();
        assertThat(createNotificationCalls()).as("起動者不明なので配送しない").isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void failNotificationPersistence() {
        willThrow(new DataIntegrityViolationException("模擬通知永続化失敗（AC-B検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private long createNotificationCalls() {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .count();
    }

    /** event-pool が空（実行中0・待ち行列0）で安定するまで待つ。「通知が来ないこと」を見る前に必ず通す。 */
    private void drainEventPool() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().pollDelay(300, TimeUnit.MILLISECONDS).atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(pool.getActiveCount()).isZero();
            assertThat(pool.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    private void awaitStatus(Long digestId, DigestStatus expected) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusOf(digestId)).isEqualTo(expected));
    }

    private DigestStatus statusOf(Long digestId) {
        return transactionTemplate.execute(tx -> digestRepository.findById(digestId).orElseThrow().getStatus());
    }

    private List<NotificationEntity> notificationsOf(Long userId, String type) {
        List<NotificationEntity> all = transactionTemplate.execute(tx ->
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent());
        return all.stream().filter(n -> type.equals(n.getNotificationType())).toList();
    }

    private Long insertGenerating(Fixture f, Long triggeredBy) {
        return transactionTemplate.execute(tx -> digestRepository.save(TimelineDigestEntity.builder()
                .scopeType(DigestScopeType.TEAM)
                .scopeId(f.teamId())
                .periodStart(LocalDateTime.now().minusDays(7))
                .periodEnd(LocalDateTime.now())
                .digestStyle(DigestStyle.valueOf("NARRATIVE"))
                .status(DigestStatus.GENERATING)
                .generatingTimeoutAt(LocalDateTime.now().plusMinutes(10))
                .triggeredBy(triggeredBy)
                .build()).getId());
    }

    private Fixture fixture(String tag) {
        String nonce = tag + "-" + System.nanoTime();
        return transactionTemplate.execute(tx -> {
            Long teamId = insertTeam("DGNTF " + nonce);
            Long userId = insertUser("dgntf-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, userId,
                    com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
            return new Fixture(teamId, userId);
        });
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'DGNTF', 'テスト', 'DGNTF テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
