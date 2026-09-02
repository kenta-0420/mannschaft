package com.mannschaft.app.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 柱②-2「販促プロビジョニング」受け入れテスト（試練・red）。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。
 * 金型: {@code LastAdminSuccessionAcceptanceIT} / {@code WebhookAuthzContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters = false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。
 * 未認証は {@code SecurityUtils.getCurrentUserId()} が投げる {@code COMMON_000} → 401 で検証する
 * （AC1・addFilters=false のため実フィルタは通らないが、コントローラ内の認証チェックは生きている）。
 * SYSTEM_ADMIN 限定の一層目（{@code SecurityConfig} の
 * {@code /api/v1/system-admin/** -> hasRole("SYSTEM_ADMIN")}）は既存の包括ルールであり、
 * 二層目の Service 単体 403 は {@code ProvisioningServiceTest}（UT）で検証する。</p>
 *
 * <p>Docker が使えない環境では {@code @EnabledIf} によりこのクラスの実行自体がスキップされる
 * （コンパイル確認のみ・CI 側で実行確認する）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱②-2 販促プロビジョニング 受け入れテスト")
class ProvisioningAcceptanceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long systemAdminId;
    private Long ordinaryUserId;

    @AfterEach
    void cleanUpAsyncAuditLogs() {
        // AuditLogService#record は @Async + 独立トランザクションでテストTXの外にcommitするため、
        // テストTXロールバックでは消えない。共有DB汚染防止のためREQUIRES_NEWで明示的に掃除する
        // （金型: OperationalAdCampaignAuditLogIT）。
        TransactionTemplate cleanupTx = new TransactionTemplate(txManager);
        cleanupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTx.execute(status -> {
            em.createNativeQuery("DELETE FROM audit_logs WHERE event_type LIKE 'PROVISIONING%'")
                    .executeUpdate();
            return null;
        });
    }

    @BeforeEach
    void setUp() {
        insertRole("SYSTEM_ADMIN", "システム管理者", 1);
        systemAdminId = insertUser("prov-sysadmin-" + System.nanoTime() + "@example.com");
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, (SELECT id FROM roles WHERE name='SYSTEM_ADMIN'), NULL, NULL, NOW(), NOW())")
                .setParameter("uid", systemAdminId)
                .executeUpdate();

        ordinaryUserId = insertUser("prov-ordinary-" + System.nanoTime() + "@example.com");

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("AC1: 未認証での作成APIは401")
    class Ac1 {

        @Test
        @DisplayName("AC1: 未認証で組織作成すると401")
        void createOrganizationWithoutAuthReturns401() throws Exception {
            SecurityContextHolder.clearContext();
            Map<String, Object> body = Map.of("name", "未認証テスト組織", "inviteEmail", "invited@example.com");

            mockMvc.perform(post("/api/v1/system-admin/provisioning/organizations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("AC3: 組織プロビジョニング作成が成功しPROVISIONED+PRIVATE+PENDING招待が残る")
    class Ac3 {

        @Test
        @DisplayName("AC3: 組織作成APIは201でPROVISIONED組織とPENDING招待を返す")
        void createOrganizationPersistsProvisionedOrgAndPendingInvitation() throws Exception {
            setAuth(systemAdminId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "販促プロビジョニング組織AC3");
            body.put("inviteEmail", "ac3-invited@example.com");

            mockMvc.perform(post("/api/v1/system-admin/provisioning/organizations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());

            // 組織が lifecycle_status=PROVISIONED, visibility=PRIVATE で保存されていること。
            Object[] org = (Object[]) em.createNativeQuery(
                            "SELECT lifecycle_status, visibility FROM organizations WHERE name = :name")
                    .setParameter("name", "販促プロビジョニング組織AC3")
                    .getSingleResult();
            assertThat(org[0]).isEqualTo("PROVISIONED");
            assertThat(org[1]).isEqualTo("PRIVATE");

            // 招待行が PENDING・token_hash が SHA-256 hex64（平文はDBに存在しない）であること。
            Object[] invitation = (Object[]) em.createNativeQuery(
                            "SELECT status, token_hash FROM provisioning_invitations WHERE invite_email = :email")
                    .setParameter("email", "ac3-invited@example.com")
                    .getSingleResult();
            assertThat(invitation[0]).isEqualTo("PENDING");
            assertThat((String) invitation[1]).matches("^[0-9a-f]{64}$");

            // AC15: 監査ログに記録されていること（@Async + 独立TXで書かれるため
            // Awaitility + REQUIRES_NEW で待つ。金型: OperationalAdCampaignAuditLogIT）。
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                TransactionTemplate newTx = new TransactionTemplate(txManager);
                newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                Long auditCount = newTx.execute(status -> ((Number) em.createNativeQuery(
                                "SELECT COUNT(*) FROM audit_logs WHERE user_id = :uid "
                                        + "AND event_type LIKE 'PROVISIONING%'")
                        .setParameter("uid", systemAdminId)
                        .getSingleResult()).longValue());
                assertThat(auditCount).isGreaterThan(0);
            });
        }
    }

    @Nested
    @DisplayName("AC5/AC15: 招待承諾でADMIN付与・スコープACTIVE化・監査記録")
    class Ac5 {

        @Test
        @DisplayName("AC5: 承諾者がADMINになりスコープがACTIVEになる")
        void acceptGrantsAdminAndActivatesScope() throws Exception {
            Long orgId = insertOrganization("AC5承諾テスト組織", "PROVISIONED");
            Long acceptorId = insertUser("ac5-acceptor-" + System.nanoTime() + "@example.com");
            // ログインユーザーのメールと招待先メールを一致させる（AC4前提の正常系）。
            String inviteEmail = em.createNativeQuery("SELECT email FROM users WHERE id = :id")
                    .setParameter("id", acceptorId)
                    .getSingleResult().toString();

            String plaintextToken = "ac5-plaintext-token-" + UUID.randomUUID();
            String tokenHash = sha256Hex(plaintextToken);
            em.createNativeQuery(
                            "INSERT INTO provisioning_invitations "
                                    + "(id, organization_id, invite_email, token_hash, status, expires_at, "
                                    + "issued_by, created_at, updated_at) "
                                    + "VALUES (UNHEX(REPLACE(UUID(),'-','')), :orgId, :email, :hash, 'PENDING', "
                                    + "DATE_ADD(NOW(), INTERVAL 7 DAY), :issuedBy, NOW(), NOW())")
                    .setParameter("orgId", orgId)
                    .setParameter("email", inviteEmail)
                    .setParameter("hash", tokenHash)
                    .setParameter("issuedBy", systemAdminId)
                    .executeUpdate();

            em.flush();
            em.clear();

            setAuth(acceptorId);
            Map<String, Object> body = Map.of("token", plaintextToken);

            mockMvc.perform(post("/api/v1/provisioning/invitations/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            String lifecycleStatus = (String) em.createNativeQuery(
                            "SELECT lifecycle_status FROM organizations WHERE id = :id")
                    .setParameter("id", orgId)
                    .getSingleResult();
            assertThat(lifecycleStatus).isEqualTo("ACTIVE");

            long adminRoleCount = ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                    + "WHERE ur.user_id = :uid AND ur.organization_id = :orgId AND r.name = 'ADMIN'")
                    .setParameter("uid", acceptorId)
                    .setParameter("orgId", orgId)
                    .getSingleResult()).longValue();
            assertThat(adminRoleCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC10: PROVISIONED組織/チームは公開検索・slug解決・公開ページで404")
    class Ac10 {

        @Test
        @DisplayName("AC10: PROVISIONEDかつPUBLICなチームの公開詳細APIは404")
        void publicTeamDetailHidesProvisionedTeam() throws Exception {
            String slug = "ac10-provisioned-" + System.nanoTime();
            insertTeamWithSlugAndVisibility("AC10隠蔽対象チーム", slug, "PUBLIC", "PROVISIONED");

            mockMvc.perform(get("/api/v1/public/teams/{slug}", slug))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("AC11: PROVISIONEDスコープへの招待発行は423/404")
    class Ac11 {

        @Test
        @DisplayName("AC11: PROVISIONEDチームへの招待トークン発行は423または404で拒否される")
        void inviteTokenCreationBlockedForProvisionedTeam() throws Exception {
            String slug = "ac11-provisioned-" + System.nanoTime();
            Long teamId = insertTeamWithSlugAndVisibility("AC11隠蔽対象チーム", slug, "PUBLIC", "PROVISIONED");
            insertUserRoleForTeam(systemAdminId, "ADMIN", teamId);

            setAuth(systemAdminId);
            Long memberRoleId = ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = 'MEMBER'")
                    .getSingleResult()).longValue();
            Map<String, Object> body = Map.of("roleId", memberRoleId, "maxUses", 1, "expiresIn", "7d");

            mockMvc.perform(post("/api/v1/teams/{slug}/invite-tokens", slug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(result -> {
                        int sc = result.getResponse().getStatus();
                        assertThat(sc).as("PROVISIONEDスコープへの招待発行は423(Locked)か404であるべき")
                                .isIn(423, 404);
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────────────────────

    private String sha256Hex(String plaintext) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority) {
        boolean exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue() > 0;
        if (exists) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
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
                                + "VALUES (:email, 'Prov', 'テスト', 'Prov テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name, String lifecycleStatus) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PRIVATE', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), :lifecycleStatus, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("lifecycleStatus", lifecycleStatus)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeamWithSlugAndVisibility(String name, String slug, String visibility, String lifecycleStatus) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, :visibility, 1, 0, 0, :slug, :lifecycleStatus, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("slug", slug)
                .setParameter("lifecycleStatus", lifecycleStatus)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private void insertUserRoleForTeam(Long userId, String roleName, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, (SELECT id FROM roles WHERE name = :roleName), :tid, NULL, NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("roleName", roleName)
                .setParameter("tid", teamId)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'TEAM', :tid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .executeUpdate();
    }
}
