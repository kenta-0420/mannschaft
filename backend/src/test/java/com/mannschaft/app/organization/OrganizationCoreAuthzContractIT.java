package com.mannschaft.app.organization;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6-B2 — organization ドメイン中核 5EP の認可契約テスト。
 *
 * <p>金型: {@link OrganizationSupporterScopeContractIT}（Wave3-B1b）。
 * 変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 *
 * <h3>対象 EP と敷設した粒度（1:1 照合表）</h3>
 * <ul>
 *   <li>PATCH /{slug} — {@code checkAdminOrAbove}（兄弟 EP renameSlug と同流儀）</li>
 *   <li>GET /{slug}/permission-groups — {@code checkAdminOrAbove}
 *       （作成/更新/削除が PermissionGroupService 側で同粒度のため読み取りも揃える）</li>
 *   <li>GET /{slug}/members/all — {@code checkAdminOrAbove}
 *       （カスケード通知の宛先選択という管理者機能。配下チームまで横断する名簿）</li>
 *   <li>GET /{slug}/teams — {@code ContentVisibilityChecker#assertCanView}
 *       （兄弟 EP getOrganization / getMembers と同じ可視性ラダー）</li>
 *   <li>POST /{slug}/transfer-ownership — {@code checkAdminOrAbove}（入口二重防御。
 *       最終判定「操作者が ADMIN」は RoleService が担うため本ガードは判定を緩めない）</li>
 * </ul>
 *
 * <p>ライフサイクル 4EP（delete/archive/unarchive/restore）は PR #2383 の担当範囲のため
 * 本テストでは扱わない。</p>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("organization 中核EP 認可契約テスト（認可根治 Wave6-B2）")
class OrganizationCoreAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private String orgASlug;
    /** 可視性ラダー検証用の PRIVATE 組織。 */
    private String privateOrgSlug;
    private Long privateOrgId;

    private Long adminAId;
    private Long memberAId;
    private Long adminBId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 5);

        orgAId = insertOrganization("ORGCORE認可契約組織A", "PUBLIC");
        orgASlug = selectSlug(orgAId);
        Long orgBId = insertOrganization("ORGCORE認可契約組織B", "PUBLIC");
        Long privateOrgId = insertOrganization("ORGCORE認可契約組織P", "PRIVATE");
        privateOrgSlug = selectSlug(privateOrgId);
        this.privateOrgId = privateOrgId;

        adminAId = insertUser("orgcore-authz-admin-a@example.com");
        memberAId = insertUser("orgcore-authz-member-a@example.com");
        adminBId = insertUser("orgcore-authz-admin-b@example.com");
        outsiderId = insertUser("orgcore-authz-outsider@example.com");

        // ADMIN 役は memberships / user_roles の両系統を満たす必要がある。
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        // memberA は組織Aの一般メンバー（ADMIN 権限なし）。
        // transfer-ownership の正当系で「譲渡先がスコープに所属している」条件も満たす。
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // 可視性ラダーの正常系（PRIVATE でもメンバーなら 200）を固定するため
        // adminA を PRIVATE 組織にも所属させる。
        MembershipTestHelper.insertMembership(
                em, adminAId, ScopeType.ORGANIZATION, privateOrgId, RoleKind.MEMBER);

        // outsiderId はどこにも所属しない。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // PATCH /{slug}（組織更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織更新(updateOrganization)")
    class UpdateOrganization {

        @Test
        @DisplayName("非メンバーの組織更新は403")
        void 非メンバーの組織更新は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の組織更新は403")
        void 一般メンバーの組織更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの越境更新は403")
        void 他組織ADMINの越境更新は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの組織更新は200（正常系）")
        void 正当ADMINの組織更新は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後の組織名"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // GET /{slug}/permission-groups（権限グループ一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("権限グループ一覧(getPermissionGroups)")
    class PermissionGroups {

        @Test
        @DisplayName("非メンバーの権限グループ一覧は403")
        void 非メンバーの権限グループ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/permission-groups", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の権限グループ一覧は403")
        void 一般メンバーの権限グループ一覧は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/permission-groups", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの権限グループ一覧は200（正常系）")
        void 正当ADMINの権限グループ一覧は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/permission-groups", orgASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // GET /{slug}/members/all（配下全メンバー名簿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("配下全メンバー一覧(getAllMembers)")
    class AllMembers {

        @Test
        @DisplayName("非メンバーの配下全メンバー取得は403")
        void 非メンバーの配下全メンバー取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/members/all", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の配下全メンバー取得は403")
        void 一般メンバーの配下全メンバー取得は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/members/all", orgASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの越境取得は403")
        void 他組織ADMINの越境取得は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/members/all", orgASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの配下全メンバー取得は200（正常系）")
        void 正当ADMINの配下全メンバー取得は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/members/all", orgASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // GET /{slug}/teams（所属チーム一覧・可視性ラダー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織所属チーム一覧(getTeams)")
    class Teams {

        @Test
        @DisplayName("PRIVATE 組織の所属チーム一覧は非メンバーに403（可視性ラダー）")
        void PRIVATE組織のチーム一覧は非メンバーに403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/teams", privateOrgSlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUBLIC 組織の所属チーム一覧は200（正常系・可視性ラダー通過）")
        void PUBLIC組織のチーム一覧は200() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/teams", orgASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRIVATE 組織でも当該組織のメンバーなら200（正常系・過剰遮断の回帰防止）")
        void PRIVATE組織でもメンバーなら200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/teams", privateOrgSlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // POST /{slug}/transfer-ownership（オーナー譲渡）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("オーナー譲渡(transferOwnership)")
    class TransferOwnership {

        @Test
        @DisplayName("非メンバーのオーナー譲渡は403")
        void 非メンバーのオーナー譲渡は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/transfer-ownership", orgASlug)
                            .param("targetUserId", String.valueOf(outsiderId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のオーナー譲渡は403（自己昇格の遮断）")
        void 一般メンバーのオーナー譲渡は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/transfer-ownership", orgASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの越境譲渡は403")
        void 他組織ADMINの越境譲渡は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/transfer-ownership", orgASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのオーナー譲渡は200（正常系）")
        void 正当ADMINのオーナー譲渡は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/transfer-ownership", orgASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** PATCH /{slug} のボディ。{@code version} は {@code @NotNull} のため必ず埋める（@Valid 先行400の回避）。 */
    private Map<String, Object> updateBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", 0L);
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
                                + "VALUES (:email, 'ORGCORE契約', 'テスト', 'ORGCORE契約テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name, String visibility) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', :visibility, 'NONE', 1, 0, "
                                + "CONCAT('orgcore-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
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
}
