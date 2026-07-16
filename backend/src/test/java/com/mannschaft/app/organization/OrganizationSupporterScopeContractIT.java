package com.mannschaft.app.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.supporter.SupporterApplicationStatus;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B1b: organization ドメイン（{@code OrganizationController} のサポーター管理 7EP）
 * API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B1b organization supporter節）・{@code AccessControlService}
 * （{@code checkAdminOrAbove}）。金型: {@code SupporterScopeContractIT}（Wave3-B5 で
 * {@code TeamController} のサポーター管理 7EP に敷設した契約テストの ORGANIZATION 版・完全な双子構成）。</p>
 *
 * <p>対象 7EP（すべて「サポーター管理（管理者向け）」区画 = checkAdminOrAbove 保護）:</p>
 * <ul>
 *   <li>GET /{slug}/supporters（承認済み一覧）</li>
 *   <li>GET /{slug}/supporter-applications（申請一覧）</li>
 *   <li>POST /{slug}/supporter-applications/{id}/approve（個別承認）</li>
 *   <li>POST /{slug}/supporter-applications/{id}/reject（個別却下）</li>
 *   <li>POST /{slug}/supporter-applications/bulk-approve（一括承認）</li>
 *   <li>GET /{slug}/supporter-settings（設定取得）</li>
 *   <li>PUT /{slug}/supporter-settings（設定更新）</li>
 * </ul>
 *
 * <p>BOLA対策: applicationId の scope 不一致は {@code SupporterService.findPendingApplicationOrThrow}
 * が既に SUPPORTER_003（存在秘匿）で保護済み（本 IT はその 404 マッピングも検証する）。
 * follow/unfollow/follow-status の 3EP はセルフサービス（本人の申請操作）のため対象外。</p>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("organization ドメイン supporter API 契約テスト（認可根治 Wave3-B1b）")
class OrganizationSupporterScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String orgASlug;
    private String orgBSlug;
    private Long orgAId;
    private Long orgBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        orgAId = insertOrganization("ORGSP認可契約組織A");
        orgBId = insertOrganization("ORGSP認可契約組織B");
        orgASlug = selectSlug(orgAId);
        orgBSlug = selectSlug(orgBId);

        adminAId = insertUser("orgsp-authz-admin-a@example.com");
        adminBId = insertUser("orgsp-authz-admin-b@example.com");
        memberAId = insertUser("orgsp-authz-member-a@example.com");
        outsiderId = insertUser("orgsp-authz-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        // memberA は組織Aの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // outsiderId はどちらの組織にも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 閲覧系（サポーター一覧・申請一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サポーター一覧(getSupporters)・申請一覧(getSupporterApplications)")
    class ListEndpoints {

        @Test
        @DisplayName("非メンバーのサポーター一覧取得は403")
        void 非メンバーのサポーター一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporters", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のサポーター一覧取得は403")
        void 一般メンバーのサポーター一覧は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporters", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのサポーター一覧取得は200")
        void 正当ADMINのサポーター一覧は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporters", orgASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの申請一覧取得は403")
        void 非メンバーの申請一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-applications", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの申請一覧取得は403（越境拒否）")
        void 他組織ADMINの申請一覧は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-applications", orgASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの申請一覧取得は200")
        void 正当ADMINの申請一覧は200() throws Exception {
            insertApplication(orgAId, memberAId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-applications", orgASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 個別承認(approve)・個別却下(reject)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個別承認(approve)・個別却下(reject)")
    class ApproveReject {

        @Test
        @DisplayName("非ADMINメンバーの承認は403")
        void 非ADMINメンバーの承認は403() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/approve",
                            orgASlug, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINが組織Aの申請IDを直指定して承認すると404（BOLA: SUPPORTER_003存在秘匿）")
        void 他組織ADMINの越境承認は404() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/approve",
                            orgBSlug, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの承認は204")
        void 正当ADMINの承認は204() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/approve",
                            orgASlug, appId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("非ADMINメンバーの却下は403")
        void 非ADMINメンバーの却下は403() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/reject",
                            orgASlug, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINが組織Aの申請IDを直指定して却下すると404（BOLA）")
        void 他組織ADMINの越境却下は404() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/reject",
                            orgBSlug, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの却下は204")
        void 正当ADMINの却下は204() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/{id}/reject",
                            orgASlug, appId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 一括承認(bulkApprove)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一括承認(bulkApprove)")
    class BulkApprove {

        @Test
        @DisplayName("非ADMINメンバーの一括承認は403")
        void 非ADMINメンバーの一括承認は403() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/bulk-approve", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINが組織Aの申請IDを一括承認しようとすると404（BOLA）")
        void 他組織ADMINの越境一括承認は404() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/bulk-approve", orgBSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));
        }

        @Test
        @DisplayName("正当ADMINの一括承認は204")
        void 正当ADMINの一括承認は204() throws Exception {
            Long appId = insertApplication(orgAId, outsiderId, SupporterApplicationStatus.PENDING);
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/supporter-applications/bulk-approve", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody(appId))))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // サポーター設定(getSupporterSettings/updateSupporterSettings)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サポーター設定(settings)")
    class Settings {

        @Test
        @DisplayName("非メンバーの設定取得は403")
        void 非メンバーの設定取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-settings", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の設定取得は403")
        void 一般メンバーの設定取得は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-settings", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの設定取得は200")
        void 正当ADMINの設定取得は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/supporter-settings", orgASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーの設定更新は403")
        void 非ADMINメンバーの設定更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/organizations/{slug}/supporter-settings", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの越境設定更新は403")
        void 他組織ADMINの越境設定更新は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/organizations/{slug}/supporter-settings", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの設定更新は200")
        void 正当ADMINの設定更新は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/organizations/{slug}/supporter-settings", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody(false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.autoApprove").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> bulkBody(Long applicationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationIds", List.of(applicationId));
        return body;
    }

    private Map<String, Object> settingsBody(boolean autoApprove) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("autoApprove", autoApprove);
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
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
                                + "VALUES (:email, 'ORGSP契約', 'テスト', 'ORGSP契約テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('orgsp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private String selectSlug(Long organizationId) {
        return (String) em.createNativeQuery("SELECT slug FROM organizations WHERE id = :id")
                .setParameter("id", organizationId)
                .getSingleResult();
    }

    /**
     * supporter_applications へ 1 行 INSERT する。
     * NOT NULL 列（scope_type/scope_id/user_id/status/created_at/updated_at）をすべて明示する。
     */
    private Long insertApplication(Long organizationId, Long applicantUserId, SupporterApplicationStatus status) {
        em.createNativeQuery(
                        "INSERT INTO supporter_applications (scope_type, scope_id, user_id, message, status, "
                                + "created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :sid, :uid, 'テスト申請', :status, NOW(), NOW())")
                .setParameter("sid", organizationId)
                .setParameter("uid", applicantUserId)
                .setParameter("status", status.name())
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM supporter_applications")
                .getSingleResult()).longValue();
    }
}
