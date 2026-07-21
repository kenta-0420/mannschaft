package com.mannschaft.app.team;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6-B2 — team ドメイン中核 11EP の認可契約テスト。
 *
 * <p>金型: {@code SupporterScopeContractIT}（Wave3-B5）／
 * {@code OrganizationSupporterScopeContractIT}（Wave3-B1b）。
 * 変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 *
 * <h3>対象 EP と敷設した粒度（1:1 照合表）</h3>
 * <ul>
 *   <li>PATCH /{slug} — {@code checkAdminOrAbove}（兄弟 EP renameSlug と同流儀）</li>
 *   <li>DELETE /{slug} — {@code checkAdminOrAbove}</li>
 *   <li>PATCH /{slug}/archive — {@code checkAdminOrAbove}</li>
 *   <li>PATCH /{slug}/unarchive — {@code checkAdminOrAbove}</li>
 *   <li>PATCH /{slug}/restore — {@code checkSystemAdmin}（チーム ADMIN でも不可）</li>
 *   <li>GET /{slug}/permission-groups — {@code checkAdminOrAbove}</li>
 *   <li>POST /{slug}/transfer-ownership — {@code checkAdminOrAbove}（入口二重防御）</li>
 *   <li>GET /{slug}/organizations — {@code ContentVisibilityChecker#assertCanView}</li>
 *   <li>GET /{slug}/followers — {@code ContentVisibilityChecker#assertCanView}</li>
 *   <li>GET /{slug}/shift-settings — {@code checkMembership}（shift ドメインの参照系と同粒度）</li>
 *   <li>PATCH /{slug}/shift-settings — {@code checkAdminOrAbove}（同変更系と同粒度）</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("team 中核EP 認可契約テスト（認可根治 Wave6-B2）")
class TeamCoreAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String teamASlug;
    /** 可視性ラダー検証用の MEMBERS_AND_ABOVE チーム。 */
    private String privateTeamSlug;

    private Long adminAId;
    private Long memberAId;
    private Long adminBId;
    private Long outsiderId;
    private Long systemAdminId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 5);
        insertRoleIfAbsent("SYSTEM_ADMIN", "システム管理者", 1);

        Long teamAId = insertTeam("TEAMCORE認可契約チームA", "PUBLIC");
        teamASlug = selectSlug(teamAId);
        Long teamBId = insertTeam("TEAMCORE認可契約チームB", "PUBLIC");
        Long privateTeamId = insertTeam("TEAMCORE認可契約チームP", "MEMBERS_AND_ABOVE");
        privateTeamSlug = selectSlug(privateTeamId);

        adminAId = insertUser("teamcore-authz-admin-a@example.com");
        memberAId = insertUser("teamcore-authz-member-a@example.com");
        adminBId = insertUser("teamcore-authz-admin-b@example.com");
        outsiderId = insertUser("teamcore-authz-outsider@example.com");
        systemAdminId = insertUser("teamcore-authz-sysadmin@example.com");

        // ADMIN 役は memberships / user_roles の両系統を満たす必要がある。
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN 権限なし）。
        // transfer-ownership の正当系で「譲渡先がスコープに所属している」条件も満たす。
        MembershipTestHelper.insertUserRole(em, memberAId, "MEMBER", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // 可視性ラダーの正常系（非 PUBLIC でもメンバーなら 200）を固定するため
        // adminA を MEMBERS_AND_ABOVE チームにも所属させる。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, privateTeamId, RoleKind.MEMBER);

        // SYSTEM_ADMIN はプラットフォームレベル割当（team_id / organization_id ともに NULL）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        // outsiderId はどこにも所属しない。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // PATCH /{slug}（チーム更新）・DELETE /{slug}（チーム削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チーム更新(updateTeam)・削除(deleteTeam)")
    class UpdateDelete {

        @Test
        @DisplayName("非メンバーのチーム更新は403")
        void 非メンバーのチーム更新は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{slug}", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のチーム更新は403")
        void 一般メンバーのチーム更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの越境更新は403")
        void 他チームADMINの越境更新は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{slug}", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("改ざん"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのチーム更新は200（正常系）")
        void 正当ADMINのチーム更新は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後のチーム名"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのチーム削除は403")
        void 非メンバーのチーム削除は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のチーム削除は403")
        void 一般メンバーのチーム削除は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのチーム削除は204（正常系）")
        void 正当ADMINのチーム削除は204() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // アーカイブ・アーカイブ解除・復元
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("アーカイブ(archiveTeam)・解除(unarchiveTeam)・復元(restoreTeam)")
    class Lifecycle {

        @Test
        @DisplayName("一般メンバー(非ADMIN)のアーカイブは403")
        void 一般メンバーのアーカイブは403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/archive", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの越境アーカイブは403")
        void 他チームADMINの越境アーカイブは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/archive", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのアーカイブは200（正常系）")
        void 正当ADMINのアーカイブは200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/archive", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のアーカイブ解除は403")
        void 一般メンバーのアーカイブ解除は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/unarchive", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのアーカイブ解除は200（正常系）")
        void 正当ADMINのアーカイブ解除は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/archive", teamASlug))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/teams/{slug}/unarchive", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("チームADMINであっても復元は403（SYSTEM_ADMIN 専用であることを固定）")
        void チームADMINの復元は403() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーの復元は403")
        void 非メンバーの復元は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("SYSTEM_ADMIN の復元は認可で弾かれない（正常系・403 にならない）")
        void SYSTEM_ADMINの復元は403にならない() throws Exception {
            setAuthentication(systemAdminId);
            // 未削除チームの復元は業務エラー（TEAM_*）になり得るが、認可（403）では弾かれないことを固定する。
            int statusCode = mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug))
                    .andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(statusCode).isNotEqualTo(403);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 権限グループ・オーナー譲渡
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("権限グループ一覧(getPermissionGroups)・オーナー譲渡(transferOwnership)")
    class AdminOnly {

        @Test
        @DisplayName("非メンバーの権限グループ一覧は403")
        void 非メンバーの権限グループ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/permission-groups", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の権限グループ一覧は403")
        void 一般メンバーの権限グループ一覧は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/permission-groups", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの権限グループ一覧は200（正常系）")
        void 正当ADMINの権限グループ一覧は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/permission-groups", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のオーナー譲渡は403（自己昇格の遮断）")
        void 一般メンバーのオーナー譲渡は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership", teamASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの越境譲渡は403")
        void 他チームADMINの越境譲渡は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership", teamASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのオーナー譲渡は200（正常系）")
        void 正当ADMINのオーナー譲渡は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership", teamASlug)
                            .param("targetUserId", String.valueOf(memberAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 可視性ラダー（所属組織一覧・フォロワー一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("所属組織一覧(getOrganizations)・フォロワー一覧(getTeamFollowers)")
    class VisibilityLadder {

        @Test
        @DisplayName("MEMBERS_AND_ABOVE チームの所属組織一覧は非メンバーに403")
        void 非公開チームの所属組織一覧は非メンバーに403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/organizations", privateTeamSlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUBLIC チームの所属組織一覧は200（正常系）")
        void 公開チームの所属組織一覧は200() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/organizations", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("MEMBERS_AND_ABOVE チームでもメンバーなら所属組織一覧は200（正常系）")
        void 非公開チームでもメンバーなら200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/organizations", privateTeamSlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("MEMBERS_AND_ABOVE チームのフォロワー一覧は非メンバーに403")
        void 非公開チームのフォロワー一覧は非メンバーに403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/followers", privateTeamSlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUBLIC チームのフォロワー一覧は200（正常系）")
        void 公開チームのフォロワー一覧は200() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/followers", teamASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // シフト設定（参照=メンバー / 変更=ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チームシフト設定(shift-settings)")
    class ShiftSettings {

        @Test
        @DisplayName("非メンバーのシフト設定取得は403")
        void 非メンバーのシフト設定取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/shift-settings", teamASlug))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのシフト設定取得は200（正常系・過剰遮断の回帰防止）")
        void 一般メンバーのシフト設定取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/shift-settings", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のシフト設定更新は403")
        void 一般メンバーのシフト設定更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/shift-settings", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(shiftSettingsBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのシフト設定更新は200（正常系）")
        void 正当ADMINのシフト設定更新は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/shift-settings", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(shiftSettingsBody())))
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

    /** PATCH /{slug}/shift-settings のボディ。最低 1 つのリマインドを有効にする必要がある。 */
    private Map<String, Object> shiftSettingsBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reminder48hEnabled", true);
        body.put("reminder24hEnabled", false);
        body.put("reminder12hEnabled", false);
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
                                + "VALUES (:email, 'TEAMCORE契約', 'テスト', 'TEAMCORE契約テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String visibility) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, :visibility, 1, 0, 0, "
                                + "CONCAT('teamcore-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private String selectSlug(Long teamId) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", teamId)
                .getSingleResult();
    }
}
