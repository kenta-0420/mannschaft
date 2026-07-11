package com.mannschaft.app.role.controller;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 権限昇格（privilege escalation / BOLA）根治の API 契約テスト（認可根治戦役 束1・AC-1-1/1-2/1-7）。
 *
 * <h2>守るバグ</h2>
 * <p>ロール変更（changeRole）・メンバー除名（removeMember）・権限グループの更新/削除は、
 * 認可チェックが皆無で「非 ADMIN メンバーが自身を ADMIN に昇格」「別スコープの ADMIN が
 * 他スコープの権限グループを改変（BOLA）」できる欠陥があった。本テストは入口 Controller ＋
 * Service 層の二重防御が効き、非 ADMIN / 別スコープ ADMIN が叩くと 403（COMMON_002）に
 * なることを実 MySQL に対して検証する。既存 ROLE_004（最後の ADMIN 除名不可）も維持する。</p>
 *
 * <h2>攻撃者と被害者スコープは別 ID（userID==teamID すり抜けの排除）</h2>
 * <ul>
 *   <li>ADMIN_A(2001): teamA / orgA の ADMIN（正当な管理者）</li>
 *   <li>MEMBER_A(2002): teamA / orgA の非 ADMIN メンバー（攻撃者）</li>
 *   <li>VICTIM(2003): teamA / orgA の一般メンバー（昇格対象の被害者）</li>
 *   <li>ADMIN_B(2004): teamB の ADMIN（別スコープ管理者 = 越境攻撃者）</li>
 * </ul>
 *
 * <p>テスト環境は {@code ddl-auto=create} + {@code flyway.enabled=false} で {@code roles} が
 * seed されないため、{@link MeControllerMembershipIntegrationTest} と同様に固定 6 ロールを
 * 本番 seed（V2.014）と同一 priority で各テスト冒頭に投入する。</p>
 */
@AutoConfigureMockMvc
@DisplayName("権限昇格根治 API 契約テスト（束1・AC-1-1/1-2/1-7）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RolePrivilegeEscalationAuthzContractTest extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_A = 2001L;
    private static final Long MEMBER_A = 2002L;
    private static final Long VICTIM = 2003L;
    private static final Long ADMIN_B = 2004L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PermissionGroupRepository permissionGroupRepository;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    private Long adminRoleId;
    private Long memberRoleId;

    private String teamASlug;
    private Long teamAId;
    private String orgASlug;
    private Long orgAId;
    private Long pgAId;

    @BeforeEach
    void setUp() {
        permissionGroupRepository.deleteAll();
        userRoleRepository.deleteAll();
        membershipRepository.deleteAll();
        teamRepository.deleteAll();
        organizationRepository.deleteAll();
        roleRepository.deleteAll();
        seedRoles();

        TeamEntity teamA = saveTeam("チームA");
        TeamEntity teamB = saveTeam("チームB");
        OrganizationEntity orgA = saveOrg("組織A");
        teamASlug = teamA.getSlug();
        teamAId = teamA.getId();
        orgASlug = orgA.getSlug();
        orgAId = orgA.getId();

        // ADMIN_A: teamA / orgA の ADMIN
        saveTeamUserRole(ADMIN_A, teamAId, adminRoleId);
        saveOrgUserRole(ADMIN_A, orgAId, adminRoleId);
        // VICTIM: teamA / orgA の MEMBER（user_roles 行を持つ = changeRole の対象になれる）
        saveTeamUserRole(VICTIM, teamAId, memberRoleId);
        saveOrgUserRole(VICTIM, orgAId, memberRoleId);
        // MEMBER_A: 非 ADMIN の一般メンバー（memberships 専属）
        saveMembership(MEMBER_A, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        saveMembership(MEMBER_A, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // ADMIN_B: teamB のみの ADMIN（teamA/orgA には無関係 = 越境攻撃者）
        saveTeamUserRole(ADMIN_B, teamB.getId(), adminRoleId);

        // teamA に属する権限グループ（BOLA 対象）
        pgAId = permissionGroupRepository.save(PermissionGroupEntity.builder()
                .teamId(teamAId)
                .name("teamA権限グループ")
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .createdBy(ADMIN_A)
                .build()).getId();
    }

    private void seedRoles() {
        saveRole("SYSTEM_ADMIN", 1);
        adminRoleId = saveRole("ADMIN", 2);
        saveRole("DEPUTY_ADMIN", 3);
        memberRoleId = saveRole("MEMBER", 4);
        saveRole("SUPPORTER", 5);
        saveRole("GUEST", 6);
    }

    private Long saveRole(String name, int priority) {
        return roleRepository.save(RoleEntity.builder()
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem("SYSTEM_ADMIN".equals(name))
                .build()).getId();
    }

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("team-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
    }

    private OrganizationEntity saveOrg(String name) {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("org-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build());
    }

    private void saveTeamUserRole(Long userId, Long teamId, Long roleId) {
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId).roleId(roleId).teamId(teamId).build());
    }

    private void saveOrgUserRole(Long userId, Long orgId, Long roleId) {
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId).roleId(roleId).organizationId(orgId).build());
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId).roleKind(roleKind)
                .joinedAt(LocalDateTime.now()).build());
    }

    private String roleChangeBody(Long roleId) {
        return "{\"roleId\":" + roleId + "}";
    }

    private String permissionGroupBody() {
        // @NotEmpty を満たす任意の permissionId（authz は validatePermissionIds より前に効くため実在不要）
        return "{\"name\":\"改変\",\"targetRole\":\"DEPUTY_ADMIN\",\"permissionIds\":[1]}";
    }

    // ───────────────────────────── AC-1-1: 権限昇格（changeRole） ─────────────────────────────

    @Test
    @WithMockUser(username = "2002")
    @DisplayName("AC-1-1a: 非ADMINメンバーがチームの他メンバーを昇格 → 403")
    void teamChangeRole_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/" + teamASlug + "/members/" + VICTIM + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleChangeBody(adminRoleId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "2002")
    @DisplayName("AC-1-1b: 非ADMINメンバーが組織の他メンバーを昇格 → 403")
    void orgChangeRole_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/organizations/" + orgASlug + "/members/" + VICTIM + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleChangeBody(adminRoleId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "2004")
    @DisplayName("AC-1-1c: 別スコープADMINが AdminDashboard で他スコープのロールを変更 → 403")
    void adminDashboardUpdateRole_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/dashboard/users/" + VICTIM + "/role")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .param("roleId", String.valueOf(adminRoleId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "2001")
    @DisplayName("AC-1-1d(正常系): 正当なチームADMINはメンバーを昇格できる → 200")
    void teamChangeRole_byValidAdmin_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/" + teamASlug + "/members/" + VICTIM + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleChangeBody(adminRoleId)))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── AC-1-2: BOLA（権限グループ） ─────────────────────────────

    @Test
    @WithMockUser(username = "2004")
    @DisplayName("AC-1-2a: 別スコープADMINが他スコープの権限グループを更新 → 403")
    void permissionGroupUpdate_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(put("/api/v1/admin/permission-groups/" + pgAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(permissionGroupBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "2004")
    @DisplayName("AC-1-2b: 別スコープADMINが他スコープの権限グループを削除 → 403")
    void permissionGroupDelete_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/permission-groups/" + pgAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "2001")
    @DisplayName("AC-1-2c(正常系): 当該スコープADMINは権限グループを削除できる → 204")
    void permissionGroupDelete_byValidScopeAdmin_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/permission-groups/" + pgAId))
                .andExpect(status().isNoContent());
    }

    // ───────────────────────────── AC-1-7: ROLE_004 非回帰 ─────────────────────────────

    @Test
    @WithMockUser(username = "2001")
    @DisplayName("AC-1-7: 最後のADMINは自身を除名できない（ROLE_004 維持）")
    void removeLastAdmin_stillBlockedByRole004() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/" + teamASlug + "/members/" + ADMIN_A))
                .andExpect(jsonPath("$.error.code").value("ROLE_004"));
    }
}
