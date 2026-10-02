package com.mannschaft.app.provisioning;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.provisioning.service.ProvisioningAcceptanceService;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 検分 P1-5(b) AC6 根治: 同一トークンへの並行 accept を実 DB（悲観ロック）で検証する。
 *
 * <p>金型: {@code RoleLastAdminConcurrencyIT}（クラス単位 {@code @Transactional} を使わず、
 * 明示的な setUp/tearDown で実データを作成・掃除する。並行スレッドは各自のトランザクションで
 * commit するため、テストトランザクションのロールバックには乗らない）。</p>
 *
 * <p>招待先メールとログインユーザーの検証済みメールの一致が AC4 で必須のため、
 * 「異なる2ユーザーが同時に同一トークンを承諾する」状況は成立しない（他人のメール宛の
 * 招待は本人以外 verified email 不一致で必ず PROV_006 になる）。したがって本テストは
 * 「同一ユーザーが同一トークンを2スレッドから同時に承諾する」形で悲観ロックの直列化を検証する:
 * 両方とも例外を投げず 200 相当（後発は AC9 の冪等応答）で終わり、かつ ADMIN role・membership が
 * 重複挿入されない（＝1件だけ）ことを確認する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱②-2 AC6: 販促プロビジョニング招待の並行accept")
class ProvisioningAcceptanceConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final long TIMEOUT_SECONDS = 15L;

    @Autowired private ProvisioningAcceptanceService acceptanceService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager em;

    private Long orgId;
    private Long acceptorId;
    private Long issuerId;
    private UUID invitationId;
    private String plaintextToken;
    private Long adminRoleId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            insertRoleIfAbsent("SYSTEM_ADMIN", 1);
            insertRoleIfAbsent("ADMIN", 2);
            insertRoleIfAbsent("MEMBER", 3);
        });
        adminRoleId = roleRepository.findByName("ADMIN").orElseThrow().getId();

        issuerId = insertUser("prov-concurrency-issuer-" + System.nanoTime() + "@example.com");
        acceptorId = insertUser("prov-concurrency-acceptor-" + System.nanoTime() + "@example.com");
        String acceptorEmail = (String) em.createNativeQuery("SELECT email FROM users WHERE id = :id")
                .setParameter("id", acceptorId).getSingleResult();

        orgId = insertOrganization("AC6並行承諾テスト組織-" + System.nanoTime(), "PROVISIONED");

        invitationId = UUID.randomUUID();
        plaintextToken = "ac6-plaintext-token-" + UUID.randomUUID();
        String tokenHash = sha256Hex(plaintextToken);
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO provisioning_invitations "
                                + "(id, organization_id, invite_email, token_hash, status, expires_at, "
                                + "issued_by, created_at, updated_at) "
                                + "VALUES (UNHEX(REPLACE(:idStr,'-','')), :orgId, :email, :hash, 'PENDING', "
                                + "DATE_ADD(NOW(), INTERVAL 7 DAY), :issuedBy, NOW(), NOW())")
                .setParameter("idStr", invitationId.toString())
                .setParameter("orgId", orgId)
                .setParameter("email", acceptorEmail)
                .setParameter("hash", tokenHash)
                .setParameter("issuedBy", issuerId)
                .executeUpdate());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("DELETE FROM provisioning_invitations WHERE id = UNHEX(REPLACE(:idStr,'-',''))")
                    .setParameter("idStr", invitationId.toString()).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE organization_id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM audit_logs WHERE event_type LIKE 'PROVISIONING%' "
                            + "AND user_id = :uid")
                    .setParameter("uid", acceptorId).executeUpdate();
            em.createNativeQuery("DELETE FROM organizations WHERE id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:acceptorId, :issuerId)")
                    .setParameter("acceptorId", acceptorId).setParameter("issuerId", issuerId).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC6: 同一トークンへの並行acceptは悲観ロックで直列化され、ADMIN/membershipは1件のみ")
    void concurrentAcceptIsSerializedByPessimisticLock() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = submit(ready, start, executor);
            Future<Throwable> second = submit(ready, start, executor);
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstError = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable secondError = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 同一actorのため両方とも例外なし（後発はAC9の冪等応答）。
            assertThat(firstError).as("1本目のaccept").isNull();
            assertThat(secondError).as("2本目のaccept（冪等応答のはず）").isNull();

            // AC6の本質: 悲観ロックにより重複挿入が起きない。
            long adminRoleCount = userRoleRepository.countByOrganizationIdAndRoleId(orgId, adminRoleId);
            assertThat(adminRoleCount).as("ADMIN role は重複挿入されない").isEqualTo(1);

            long membershipCount = membershipRepository
                    .findActiveByUserAndScope(acceptorId, ScopeType.ORGANIZATION, orgId)
                    .stream().count();
            assertThat(membershipCount).as("membership は重複挿入されない").isEqualTo(1);

            String lifecycleStatus = (String) em.createNativeQuery(
                            "SELECT lifecycle_status FROM organizations WHERE id = :id")
                    .setParameter("id", orgId).getSingleResult();
            assertThat(lifecycleStatus).isEqualTo("ACTIVE");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<Throwable> submit(CountDownLatch ready, CountDownLatch start, ExecutorService executor) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrency test start timed out");
            }
            try {
                acceptanceService.accept(plaintextToken, acceptorId);
                return null;
            } catch (Throwable error) {
                return error;
            }
        });
    }

    private void insertRoleIfAbsent(String name, int priority) {
        boolean exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue() > 0;
        if (exists) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                .setParameter("name", name).setParameter("priority", priority).executeUpdate();
    }

    private Long insertUser(String email) {
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'Prov', 'テスト', 'Prov テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).executeUpdate());
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String lifecycleStatus) {
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PRIVATE', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), :lifecycleStatus, NOW(), NOW())")
                .setParameter("name", name).setParameter("lifecycleStatus", lifecycleStatus).executeUpdate());
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
