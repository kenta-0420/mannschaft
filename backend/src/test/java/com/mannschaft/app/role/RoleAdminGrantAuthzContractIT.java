package com.mannschaft.app.role;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 副管理者（DEPUTY_ADMIN）によるロール変更の権限範囲を管理者（ADMIN）に限定する契約テスト。
 *
 * <p>「ADMIN への昇格」と「ADMIN のロールを別ロールへ変える操作」は、そのスコープの ADMIN だけができる。
 * DEPUTY_ADMIN は 403（COMMON_002）。DEPUTY_ADMIN による ADMIN 以外のロール変更は従来どおり許可する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ロール変更の権限範囲（ADMIN限定）契約テスト")
class RoleAdminGrantAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private String teamSlug;
    private Long orgId;
    private String orgSlug;

    private Long teamAdmin;
    private Long teamDeputy;
    private Long teamDeputy2;
    private Long teamMember;
    private Long orgAdmin;
    private Long orgDeputy;
    private Long orgMember;

    @BeforeEach
    void setUp() {
        teamId = insertScope("teams", "RGA チーム");
        teamSlug = slugOf("teams", teamId);
        orgId = insertScope("organizations", "RGA 組織");
        orgSlug = slugOf("organizations", orgId);

        teamAdmin = seed("rga-t-admin@example.com", ScopeType.TEAM, teamId, "ADMIN");
        teamDeputy = seed("rga-t-deputy@example.com", ScopeType.TEAM, teamId, "DEPUTY_ADMIN");
        teamDeputy2 = seed("rga-t-deputy2@example.com", ScopeType.TEAM, teamId, "DEPUTY_ADMIN");
        teamMember = seed("rga-t-member@example.com", ScopeType.TEAM, teamId, "GUEST");
        orgAdmin = seed("rga-o-admin@example.com", ScopeType.ORGANIZATION, orgId, "ADMIN");
        orgDeputy = seed("rga-o-deputy@example.com", ScopeType.ORGANIZATION, orgId, "DEPUTY_ADMIN");
        orgMember = seed("rga-o-member@example.com", ScopeType.ORGANIZATION, orgId, "GUEST");
        // roles に MEMBER が無いと変更先ロールを解決できないため、insertMembership の冪等 seed 副作用で用意する。
        MembershipTestHelper.insertMembership(em, insertUser("rga-seed@example.com"),
                ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("チーム: 副管理者が自分を ADMIN にすると403でロール不変")
    void team_deputy_self_promote_forbidden() throws Exception {
        assertForbidden(teamDeputy, teamUrl(teamDeputy), "ADMIN");
        assertThat(roleOf(teamDeputy, "team_id", teamId)).isEqualTo("DEPUTY_ADMIN");
    }

    @Test
    @DisplayName("チーム: 副管理者が他メンバーを ADMIN にすると403でロール不変")
    void team_deputy_promote_other_forbidden() throws Exception {
        assertForbidden(teamDeputy, teamUrl(teamMember), "ADMIN");
        assertThat(roleOf(teamMember, "team_id", teamId)).isEqualTo("GUEST");
    }

    @Test
    @DisplayName("チーム: 副管理者が ADMIN を別ロールにすると403でロール不変")
    void team_deputy_demote_admin_forbidden() throws Exception {
        assertForbidden(teamDeputy, teamUrl(teamAdmin), "MEMBER");
        assertThat(roleOf(teamAdmin, "team_id", teamId)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("チーム: ADMIN が他メンバーを ADMIN にすると200")
    void team_admin_promote_ok() throws Exception {
        setAuth(teamAdmin);
        perform(teamUrl(teamMember), "ADMIN").andExpect(status().isOk());
        assertThat(roleOf(teamMember, "team_id", teamId)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("チーム: 副管理者による ADMIN 以外へのロール変更は従来どおり200")
    void team_deputy_non_admin_change_ok() throws Exception {
        setAuth(teamDeputy);
        perform(teamUrl(teamMember), "DEPUTY_ADMIN").andExpect(status().isOk());
        assertThat(roleOf(teamMember, "team_id", teamId)).isEqualTo("DEPUTY_ADMIN");
        perform(teamUrl(teamDeputy2), "GUEST").andExpect(status().isOk());
    }

    @Test
    @DisplayName("組織: 副管理者が自分を ADMIN にすると403でロール不変")
    void org_deputy_self_promote_forbidden() throws Exception {
        assertForbidden(orgDeputy, orgUrl(orgDeputy), "ADMIN");
        assertThat(roleOf(orgDeputy, "organization_id", orgId)).isEqualTo("DEPUTY_ADMIN");
    }

    @Test
    @DisplayName("組織: 副管理者が ADMIN を別ロールにすると403でロール不変")
    void org_deputy_demote_admin_forbidden() throws Exception {
        assertForbidden(orgDeputy, orgUrl(orgAdmin), "MEMBER");
        assertThat(roleOf(orgAdmin, "organization_id", orgId)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("組織: ADMIN が他メンバーを ADMIN にすると200")
    void org_admin_promote_ok() throws Exception {
        setAuth(orgAdmin);
        perform(orgUrl(orgMember), "ADMIN").andExpect(status().isOk());
        assertThat(roleOf(orgMember, "organization_id", orgId)).isEqualTo("ADMIN");
    }

    // ---------------------------------------------------------------- helpers

    private void assertForbidden(Long actor, String url, String roleName) throws Exception {
        setAuth(actor);
        perform(url, roleName).andExpect(status().isForbidden());
    }

    private ResultActions perform(String url, String roleName) throws Exception {
        return mockMvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":" + roleId(roleName) + "}"));
    }

    private String teamUrl(Long userId) {
        return "/api/v1/teams/" + teamSlug + "/members/" + userId + "/role";
    }

    private String orgUrl(Long userId) {
        return "/api/v1/organizations/" + orgSlug + "/members/" + userId + "/role";
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private String roleOf(Long userId, String scopeColumn, Long scopeId) {
        em.flush();
        em.clear();
        return (String) em.createNativeQuery("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = :u AND ur." + scopeColumn + " = :s")
                .setParameter("u", userId).setParameter("s", scopeId).getSingleResult();
    }

    /** ユーザー作成＋所属＋user_roles の権限ロール割当（roles は insertUserRole が冪等 seed する）。 */
    private Long seed(String email, ScopeType scope, Long scopeId, String roleName) {
        Long uid = insertUser(email);
        MembershipTestHelper.insertMembership(em, uid, scope, scopeId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, uid, roleName,
                scope == ScopeType.TEAM ? scopeId : null,
                scope == ScopeType.ORGANIZATION ? scopeId : null);
        return uid;
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
                                + "VALUES (:email, 'RGA', 'テスト', 'RGA テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertScope(String table, String name) {
        String sql = "teams".equals(table)
                ? "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                + "CONCAT('rga-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())"
                : "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                + "supporter_enabled, version, slug, created_at, updated_at) "
                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                + "CONCAT('rga-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())";
        em.createNativeQuery(sql).setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM " + table + " WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private String slugOf(String table, Long id) {
        return (String) em.createNativeQuery("SELECT slug FROM " + table + " WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
}
