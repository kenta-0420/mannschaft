package com.mannschaft.app.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 柱①「ADMINゼロ根治」AC10 — 同時脱退の直列化契約テスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12。
 * 金型: {@code BillingOperationAuthorizerConcurrencyIT}（class 単位 {@code @Transactional} を
 * 使わず {@code TransactionTemplate} でセットアップ／後始末を独立コミットする方式）。</p>
 *
 * <p><b>なぜ {@code requestWithdrawal} ではなく {@code leaveScope} で検証するか</b>:
 * {@code requestWithdrawal}（退会受付）は {@code users.deleted_at} を立てるだけで
 * {@code user_roles} を即座には変更しない（実削除は30日後の purge）。したがって
 * 「他メンバー1人以上のスコープで ADMIN 数 0 が同時に発生しない」という §12.1 の不変条件を
 * 実際に破りうる操作は {@code user_roles} を直接変更する経路（除名・委譲・脱退・降格）である。
 * 本テストは {@code RoleService#leaveScope}（{@code DELETE /teams/{slug}/me}）を選び、
 * 2 人の ADMIN が同時に自主脱退した場合に、既存 {@code AdminRoleMutationLockService} の
 * 悲観ロックがレースを直列化し、片方だけが成功する（もう片方は ROLE_004 で 409）ことを確認する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱①ADMINゼロ根治 AC10 同時脱退の直列化契約")
class LastAdminConcurrentLeaveScopeIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private String teamSlug;
    private Long admin1Id;
    private Long admin2Id;
    private Long memberId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            teamSlug = "lascc-team-" + Long.toHexString(System.nanoTime());
            teamId = insertTeam("LASCC チーム", teamSlug);

            admin1Id = insertUser("lascc-admin1-" + System.nanoTime() + "@example.com");
            admin2Id = insertUser("lascc-admin2-" + System.nanoTime() + "@example.com");
            memberId = insertUser("lascc-member-" + System.nanoTime() + "@example.com");

            MembershipTestHelper.insertMembership(em, admin1Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, admin1Id, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, admin2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, admin2Id, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        });
    }

    @AfterEach
    void tearDown() {
        if (teamId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id = :tid")
                    .setParameter("tid", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:a1, :a2, :m)")
                    .setParameter("a1", admin1Id).setParameter("a2", admin2Id).setParameter("m", memberId)
                    .executeUpdate();
        });
    }

    @Test
    @DisplayName("AC10: ADMIN2人が同時脱退しても他メンバー1人以上のスコープでADMIN数0が発生しない")
    void 並行脱退でADMIN数0が発生しない() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Integer>> futures = List.of(
                pool.submit(() -> leaveConcurrently(admin1Id, ready, go)),
                pool.submit(() -> leaveConcurrently(admin2Id, ready, go)));
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        for (Future<Integer> f : futures) {
            int status = f.get(10, TimeUnit.SECONDS);
            if (status == 409) {
                conflictCount.incrementAndGet();
            } else if (status == 204) {
                successCount.incrementAndGet();
            }
        }
        pool.shutdown();

        // 既存 AdminRoleMutationLockService による直列化: 片方だけ脱退が成功し、
        // もう片方は「最後のADMIN」判定で409（ROLE_004）になるはず。
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        long remainingAdmins = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                + "WHERE ur.team_id = :tid AND r.name = 'ADMIN'")
                .setParameter("tid", teamId)
                .getSingleResult()).longValue();
        assertThat(remainingAdmins).isEqualTo(1L);
    }

    private int leaveConcurrently(Long userId, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
            return mockMvc.perform(delete("/api/v1/teams/{slug}/me", teamSlug))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                                + "VALUES (:email, 'LASCC', 'テスト', 'LASCC テスト', 'ACTIVE', "
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
