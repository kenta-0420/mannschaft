package com.mannschaft.app.role;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.dto.TransferOwnershipOfferCreateRequest;
import com.mannschaft.app.role.entity.OwnershipTransferOfferEntity;
import com.mannschaft.app.role.repository.OwnershipTransferOfferRepository;
import com.mannschaft.app.role.service.OwnershipTransferOfferService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2997 / CMP-260827-1152 G5（role）— オーナー委譲オファーの打診到達通知・辞退通知の
 * 業務TX分離を実 MySQL で検証する。
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <p>現行は業務TXの内側で {@code NotificationHelper#notify}（既定 {@code REQUIRED} で
 * {@code NotificationService#createNotification}）を同期実行する。そのため業務TXがロールバックする場合でも
 * ロールバック前に通知生成が呼ばれ（AC-A が red）、通知側の DB 例外が rollback-only を残して打診・辞退の
 * 確定まで巻き戻しうる（AC-B）。</p>
 *
 * <h2>観測方法と限界</h2>
 * <p>{@link NotificationService} を {@code @MockitoSpyBean} で spy して {@code createNotification} の呼び出しを観測する。
 * AC-B は spy が入口で例外を投げる形の注入であり、実 MySQL の通知 INSERT 自体を失敗させる形ではない
 * （recruitment の {@code RecruitmentNotificationTransactionIT} と同じ限界）。注入点への到達は呼び出し回数の
 * await で示す。AC-A は event-pool が空になるまで待ってから 0 件を確認する（sleep のみの 0 件判定はしない）。</p>
 *
 * <p>クラスに {@code @Transactional} を付けない（付けると commit が起きず AFTER_COMMIT が発火しない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G5 role オーナー委譲の付随通知のTX分離（実DB）")
class OwnershipTransferNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String OFFERED = "OWNERSHIP_TRANSFER_OFFERED";
    private static final String DECLINED = "OWNERSHIP_TRANSFER_DECLINED";

    @Autowired private OwnershipTransferOfferService offerService;
    @Autowired private OwnershipTransferOfferRepository offerRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("event-pool") private java.util.concurrent.Executor eventPool;

    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private NotificationService notificationService;

    private Long teamId;
    private String teamSlug;
    private Long adminId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        awaitEventPoolIdle();
        String nonce = Long.toString(System.nanoTime(), 36);
        teamSlug = "g5role-" + nonce;
        teamId = txTemplate.execute(s -> insertTeam("G5 role " + nonce, teamSlug));
        adminId = txTemplate.execute(s -> insertUser("g5role-admin-" + nonce + "@example.com"));
        targetId = txTemplate.execute(s -> insertUser("g5role-target-" + nonce + "@example.com"));
        txTemplate.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, targetId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.createNativeQuery("INSERT INTO two_factor_auth (user_id, totp_secret, backup_codes, is_enabled, "
                            + "verified_at, created_at, updated_at) "
                            + "VALUES (:uid, 'G5ROLETESTSECRET', '[]', 1, NOW(), NOW(), NOW())")
                    .setParameter("uid", targetId)
                    .executeUpdate();
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // createOffer（打診到達通知）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("createOffer AC-A: コミット直前に強制 rollback されたら通知生成は一度も呼ばれず、打診も残らない")
    void createOffer_ロールバック時は通知が作られない() {
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            offerService.createOffer(teamId, "TEAM", teamSlug,
                    new TransferOwnershipOfferCreateRequest(targetId), adminId);
            failAtBeforeCommit();
        })).isInstanceOf(RuntimeException.class);

        awaitEventPoolIdle();
        assertThat(createNotificationCalls()).as("通知生成が呼ばれていないこと").isZero();
        assertThat(notificationRows(targetId, OFFERED)).isZero();
        assertThat(offerRepository.findByTeamIdAndStatus(teamId, "PENDING")).isEmpty();
    }

    @Test
    @DisplayName("createOffer AC-A2: コミットされたら打診相手に OWNERSHIP_TRANSFER_OFFERED の通知行が作られる")
    void createOffer_コミット後に通知が作られる() {
        offerService.createOffer(teamId, "TEAM", teamSlug,
                new TransferOwnershipOfferCreateRequest(targetId), adminId);

        awaitNotificationRows(targetId, OFFERED, 1);
    }

    @Test
    @DisplayName("createOffer AC-B: 通知の永続化が失敗しても打診は PENDING で残り、呼び出しは正常に返る")
    void createOffer_通知失敗でも打診は残る() {
        failNotificationPersistence();

        offerService.createOffer(teamId, "TEAM", teamSlug,
                new TransferOwnershipOfferCreateRequest(targetId), adminId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
        assertThat(offerRepository.findByTeamIdAndStatus(teamId, "PENDING")).hasSize(1);
        assertThat(notificationRows(targetId, OFFERED)).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // declineOffer（辞退通知）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("declineOffer AC-A: コミット直前に強制 rollback されたら通知生成は呼ばれず、打診は PENDING のまま")
    void declineOffer_ロールバック時は通知が作られない() {
        UUID offerId = seedPendingOffer();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            offerService.declineOffer(teamId, "TEAM", teamSlug, offerId, targetId);
            failAtBeforeCommit();
        })).isInstanceOf(RuntimeException.class);

        awaitEventPoolIdle();
        assertThat(createNotificationCalls()).isZero();
        assertThat(notificationRows(adminId, DECLINED)).isZero();
        assertThat(offerStatus(offerId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("declineOffer AC-A2: コミットされたら発行者に OWNERSHIP_TRANSFER_DECLINED の通知行が作られる")
    void declineOffer_コミット後に通知が作られる() {
        UUID offerId = seedPendingOffer();

        offerService.declineOffer(teamId, "TEAM", teamSlug, offerId, targetId);

        awaitNotificationRows(adminId, DECLINED, 1);
        assertThat(offerStatus(offerId)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("declineOffer AC-B: 通知の永続化が失敗しても辞退（DECLINED）は残り、呼び出しは正常に返る")
    void declineOffer_通知失敗でも辞退は残る() {
        UUID offerId = seedPendingOffer();
        failNotificationPersistence();

        offerService.declineOffer(teamId, "TEAM", teamSlug, offerId, targetId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
        assertThat(offerStatus(offerId)).isEqualTo("DECLINED");
        assertThat(notificationRows(adminId, DECLINED)).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void failAtBeforeCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("コミット直前の強制失敗（AC-A検証用）");
            }
        });
    }

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

    private int notificationRows(Long userId, String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND notification_type = ?",
                Integer.class, userId, type);
        return n == null ? 0 : n;
    }

    private void awaitNotificationRows(Long userId, String type, int expected) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRows(userId, type)).isEqualTo(expected));
    }

    /** event-pool の実行中・待機中タスクがなくなるまで待つ（AC-A の「非同期完了」の待機印）。 */
    private void awaitEventPoolIdle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().atMost(60, TimeUnit.SECONDS).until(() ->
                pool.getThreadPoolExecutor().getQueue().isEmpty() && pool.getActiveCount() == 0);
    }

    private UUID seedPendingOffer() {
        return txTemplate.execute(s -> offerRepository.save(OwnershipTransferOfferEntity.builder()
                .teamId(teamId)
                .issuedBy(adminId)
                .targetUserId(targetId)
                .status("PENDING")
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .build()).getId());
    }

    private String offerStatus(UUID offerId) {
        return txTemplate.execute(s -> offerRepository.findById(offerId).orElseThrow().getStatus());
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
                                + "VALUES (:email, 'G5ROLE', 'テスト', 'G5ROLE テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
