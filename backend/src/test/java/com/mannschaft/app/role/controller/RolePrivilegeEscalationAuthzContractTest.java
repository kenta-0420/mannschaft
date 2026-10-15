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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
@DisplayName("権限昇格根治 API 契約テスト（束1・AC-1-1/1-2/1-7）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RolePrivilegeEscalationAuthzContractTest extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_A = 2001L;
    private static final Long MEMBER_A = 2002L;
    private static final Long VICTIM = 2003L;
    private static final Long ADMIN_B = 2004L;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

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
        // CMP-052: 本フィクスチャは user_roles / memberships だけを張り users 行を作っていなかった。
        // 本番では在籍者は必ず users 行を持つ（ログインは PENDING_VERIFICATION / FROZEN を弾くため、
        // スコープに在籍して操作対象になりうるのは ACTIVE なアカウントに限られる）ので、
        // 「users 行が無い在籍者」は本番で成立しえない状態である。
        // ロール変更経路の生存確認（isActiveUser）が入ったことでこの欠落が表面化したため、
        // ガードを緩めるのではなくフィクスチャ側を本番で成立する状態へ是正する。
        insertActiveUser(ADMIN_A, "rpe-admin-a");
        insertActiveUser(MEMBER_A, "rpe-member-a");
        insertActiveUser(VICTIM, "rpe-victim");
        insertActiveUser(ADMIN_B, "rpe-admin-b");

        // roles はグローバル参照テーブル（本番は V2.014 で seed）。共有 Testcontainer を汚さないため、
        // 削除・再INSERT せず name で既存を引く（無ければ idempotent に作成）。本クラスは @Transactional なので
        // 全 seed はテスト毎にロールバックされ、同一 Testcontainer を共有する他テスト
        // （例: ScheduleVisibilityResolverIntegrationTest の無条件 INSERT MEMBER）と衝突しない。
        ensureRoles();

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

    private void ensureRoles() {
        ensureRole("SYSTEM_ADMIN", 1);
        adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("DEPUTY_ADMIN", 3);
        memberRoleId = ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);
    }

    /**
     * roles を name で引き、無ければ本番 V2.014 と同一 priority で作成して id を返す
     * （idempotent・グローバル参照テーブルを破壊しない）。
     */
    private Long ensureRole(String name, int priority) {
        return roleRepository.findByName(name)
                .map(RoleEntity::getId)
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name(name)
                        .displayName(name)
                        .priority(priority)
                        .isSystem("SYSTEM_ADMIN".equals(name))
                        .build()).getId());
    }

    /**
     * 固定 ID の ACTIVE ユーザーを users に投入する（CMP-052）。
     *
     * <p>{@code @WithMockUser(username = "2001")} 等でユーザー ID を固定しているため、
     * IDENTITY 採番に任せられない。test profile は {@code ddl-auto=create} + Flyway 無効で
     * schema が Entity 由来のため、NOT NULL 列を手動で全充填する必要がある
     * （他 IT の {@code insertUser} と同じ列並び ＋ id 明示）。本クラスは {@code @Transactional} なので
     * 共有 Testcontainer には残らない。</p>
     */
    private void insertActiveUser(Long id, String emailPrefix) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "id, email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:id, :email, '権限', '昇格', '権限昇格', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("email", emailPrefix + "-" + SLUG_SEQ.incrementAndGet() + "@example.com")
                .executeUpdate();
    }

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("rpe-team-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
    }

    private OrganizationEntity saveOrg(String name) {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("rpe-org-" + SLUG_SEQ.incrementAndGet())
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
