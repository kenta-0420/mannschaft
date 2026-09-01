package com.mannschaft.app.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 柱①「ADMINゼロ根治」— deletion-preview / 退会 API 経由の受け入れテスト（試練・red）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11〜§14。
 * 金型: {@code OwnershipTransferOfferScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 *
 * <p>本テストは骨格未配線（{@code UserService#requestWithdrawal} は現状ガードを呼ばず、
 * {@code GdprController#buildDeletionPreview} は {@code lastAdminScopes} を充填しない）の
 * ため、既存の実際の挙動に対して受け入れ条件を検証すると red になる。実装（出陣）で
 * 骨格クラスを配線した時点で green化する設計。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱①ADMINゼロ根治 受け入れテスト（deletion-preview / 退会API）")
class LastAdminSuccessionAcceptanceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long adminId;   // teamの唯一のADMIN
    private Long memberId;  // teamの他メンバー

    @BeforeEach
    void setUp() {
        String slug = "lasacc-team-" + Long.toHexString(System.nanoTime());
        teamId = insertTeam("LASACC チーム", slug);

        adminId = insertUser("lasacc-admin-" + System.nanoTime() + "@example.com");
        memberId = insertUser("lasacc-member-" + System.nanoTime() + "@example.com");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("AC1: 他メンバー1人以上のスコープで唯一のADMINが退会要求 → 409＋新エラーコード")
    class Ac1 {

        @Test
        @DisplayName("AC1: 唯一のADMINが退会要求すると409＋GDPR_011が返る")
        void 唯一のADMINが退会要求すると409() throws Exception {
            setAuth(adminId);

            mockMvc.perform(delete("/api/v1/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(withdrawalBody())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("GDPR_011"));
        }

        @Test
        @DisplayName("AC1: deletion-previewのlastAdminScopesにscopeType/scopeId/scopeName/otherMembersCountが列挙される")
        void deletionPreviewにlastAdminScopesが列挙される() throws Exception {
            setAuth(adminId);

            mockMvc.perform(get("/api/v1/account/deletion-preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lastAdminScopes[0].scopeType").value("TEAM"))
                    .andExpect(jsonPath("$.data.lastAdminScopes[0].scopeId").value(teamId))
                    .andExpect(jsonPath("$.data.lastAdminScopes[0].scopeName").exists())
                    .andExpect(jsonPath("$.data.lastAdminScopes[0].otherMembersCount").value(1));
        }
    }

    @Nested
    @DisplayName("AC2: 承諾型委譲完了 or アーカイブ後、previewのlastAdminScopesが空になり退会成功")
    class Ac2 {

        @Test
        @DisplayName("AC2: 委譲でmemberIdがADMINになった後は退会が成功する")
        void 委譲完了後は退会成功() throws Exception {
            // 承継完了状態を直接 seed する（承諾型オファーのフロー自体は F01.2 側の管轄）
            em.createNativeQuery(
                            "UPDATE user_roles SET role_id = "
                                    + "(SELECT id FROM roles WHERE name = 'ADMIN') "
                                    + "WHERE user_id = :uid AND team_id = :tid")
                    .setParameter("uid", adminId)
                    .setParameter("tid", teamId)
                    .executeUpdate();
            // 単純化: adminId を降格し memberId をADMIN化（本来は transferOwnership 経由）
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid AND team_id = :tid")
                    .setParameter("uid", adminId)
                    .setParameter("tid", teamId)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                    + "VALUES (:uid, (SELECT id FROM roles WHERE name = 'ADMIN'), :tid, NULL, NOW(), NOW())")
                    .setParameter("uid", memberId)
                    .setParameter("tid", teamId)
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuth(adminId);
            mockMvc.perform(get("/api/v1/account/deletion-preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lastAdminScopes").isEmpty());

            mockMvc.perform(delete("/api/v1/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(withdrawalBody())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("AC3: 他メンバー0人のスコープは退会ブロックされない")
    class Ac3 {

        @Test
        @DisplayName("AC3: 他メンバー0人のスコープの唯一ADMINは退会をブロックされない")
        void 他メンバー0人は退会ブロックされない() throws Exception {
            // memberId を脱退させ、adminId が唯一のメンバーになる状態を作る
            em.createNativeQuery("UPDATE memberships SET left_at = NOW() "
                            + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("uid", memberId)
                    .setParameter("tid", teamId)
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuth(adminId);
            mockMvc.perform(delete("/api/v1/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(withdrawalBody())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("AC10: 同時退会の直列化 — 他メンバー1人以上のスコープのADMIN数0が発生しない")
    class Ac10 {

        @Test
        @DisplayName("AC10: ADMIN2人が並行退会要求しても他メンバー1人以上のスコープでADMIN数0が発生しない")
        void 並行退会でADMIN数0が発生しない() throws Exception {
            // teamにもう1人ADMINを追加し、2人同時に退会要求させる
            Long admin2Id = insertUser("lasacc-admin2-" + System.nanoTime() + "@example.com");
            MembershipTestHelper.insertMembership(em, admin2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, admin2Id, "ADMIN", teamId, null);
            em.flush();
            em.clear();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger conflictCount = new AtomicInteger();

            List<Future<Integer>> futures = List.of(
                    pool.submit(() -> withdrawConcurrently(adminId, ready, go)),
                    pool.submit(() -> withdrawConcurrently(admin2Id, ready, go)));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            for (Future<Integer> f : futures) {
                int status = f.get(10, TimeUnit.SECONDS);
                if (status == 409) {
                    conflictCount.incrementAndGet();
                }
            }
            pool.shutdown();

            // 直列化されていれば、他メンバー1人以上のスコープのADMINが同時に0人になることは
            // 許されないため、少なくとも片方は409で拒否されるはず。
            assertThat(conflictCount.get()).isGreaterThanOrEqualTo(1);

            long remainingAdmins = ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                    + "WHERE ur.team_id = :tid AND r.name = 'ADMIN'")
                    .setParameter("tid", teamId)
                    .getSingleResult()).longValue();
            assertThat(remainingAdmins).isGreaterThanOrEqualTo(1);
        }

        private int withdrawConcurrently(Long userId, CountDownLatch ready, CountDownLatch go) {
            try {
                ready.countDown();
                go.await();
                setAuth(userId);
                return mockMvc.perform(delete("/api/v1/users/me")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(withdrawalBody())))
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> withdrawalBody() {
        return new LinkedHashMap<>();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'LASACC', 'テスト', 'LASACC テスト', 'ACTIVE', "
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
