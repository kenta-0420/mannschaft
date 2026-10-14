package com.mannschaft.app.role.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code MeController}（GET /api/v1/me/teams・/api/v1/me/organizations）の所属一覧 API 契約テスト。
 *
 * <h2>守るバグ（#1357 同型退行の取りこぼし）</h2>
 * <p>F00.5 で MEMBER / SUPPORTER の所属は {@code user_roles} から {@code memberships} へ移管されたが、
 * 本 2 エンドポイントが旧 {@code user_roles} 専属のまま放置されていたため、{@code memberships} 専属で
 * 所属するユーザーの組織・チームが API から丸ごと欠落していた。本テストは所属一覧が
 * 「{@code user_roles} ∪ {@code memberships}」の和集合で返ること、役割名が priority 最強で統合解決される
 * こと、退会済み（{@code left_at IS NOT NULL}）が除外されることを実 MySQL に対して検証する。</p>
 *
 * <h2>受け入れ条件（AC）</h2>
 * <ul>
 *   <li>AC1: memberships 専属（user_roles 該当行なし）の org/team が一覧に含まれる</li>
 *   <li>AC2: user_roles と memberships 両方に所属がある org/team は 1 件で返る（重複しない）</li>
 *   <li>AC3: role は priority 最強で解決される（user_roles=ADMIN ∧ membership=MEMBER → "ADMIN"）</li>
 *   <li>AC4: SUPPORTER 専属（membership role_kind=SUPPORTER のみ）の org/team は role="SUPPORTER"</li>
 *   <li>AC5: 退会済み（left_at IS NOT NULL）の membership は一覧に含まれない</li>
 *   <li>AC6: 上記を organizations・teams 双方で満たす</li>
 * </ul>
 *
 * <p>テスト環境は {@code application-test.yml} の {@code ddl-auto=create} + {@code flyway.enabled=false} のため
 * {@code roles} テーブルが seed されない。{@link AccessControlService#resolveEffectiveRoleName} が
 * roles の priority を引くため、各テストの冒頭で固定 6 ロールを本番 seed（V2.014）と同一 priority で投入する。</p>
 */
@AutoConfigureMockMvc
@DisplayName("MeController 所属一覧 memberships 統合 API 契約テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class MeControllerMembershipIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AccessControlService accessControlService;

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

    /** ユニーク slug 生成用。テスト間でデータが残るため衝突を避ける。 */
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    private Long deputyRoleId;
    private Long systemRoleId;
    private Long memberRoleId;
    private Long adminRoleId;

    @BeforeEach
    void setUp() {
        // 各テストは独立性のため全関連テーブルを掃除する（singleton container で DB は共有される）。
        // roles は IDENTITY 採番で save の INSERT が即時実行される一方、deleteAll の DELETE は flush まで保留される。
        // 他の IT が roles にコミットした行（SYSTEM_ADMIN 等）が残っていると INSERT が先に走って一意制約違反になるため、
        // 再作成の前に DELETE を確定させる（roles が空である前提に頼らない）。
        userRoleRepository.deleteAll();
        userRoleRepository.flush();
        membershipRepository.deleteAll();
        membershipRepository.flush();
        teamRepository.deleteAll();
        teamRepository.flush();
        organizationRepository.deleteAll();
        organizationRepository.flush();
        roleRepository.deleteAll();
        roleRepository.flush();
        seedRoles();
    }

    /** 本番 V2.014 と同一 priority で固定 6 ロールを投入する（priority 小 = 強）。 */
    private void seedRoles() {
        systemRoleId = saveRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        adminRoleId = saveRole("ADMIN", "管理者", 2, false);
        deputyRoleId = saveRole("DEPUTY_ADMIN", "副管理者", 3, false);
        memberRoleId = saveRole("MEMBER", "メンバー", 4, false);
        saveRole("SUPPORTER", "サポーター", 5, false);
        saveRole("GUEST", "ゲスト", 6, false);
    }

    private Long saveRole(String name, String displayName, int priority, boolean isSystem) {
        return roleRepository.save(RoleEntity.builder()
                .name(name)
                .displayName(displayName)
                .priority(priority)
                .isSystem(isSystem)
                .build()).getId();
    }

    // ───────────────────────────────────────────────────────────────────
    // テストデータ構築ヘルパー
    // ───────────────────────────────────────────────────────────────────

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
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .teamId(teamId)
                .build());
    }

    private void saveOrgUserRole(Long userId, Long orgId, Long roleId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .organizationId(orgId)
                .build());
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind, boolean active) {
        MembershipTestHelper.insertActiveUser(em, userId);
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .leftAt(active ? null : LocalDateTime.now())
                .leaveReason(active ? null : com.mannschaft.app.membership.domain.LeaveReason.SELF)
                .build());
    }

    // ───────────────────────────────────────────────────────────────────
    // AC1: memberships 専属（user_roles 該当行なし）が一覧に含まれる
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "1001")
    @DisplayName("AC1-org: membership 専属（user_roles 行なし）の組織が /me/organizations に含まれる")
    void ac1_org_membershipOnly_included() throws Exception {
        Long userId = 1001L;
        OrganizationEntity org = saveOrg("メンバーシップ専属組織");
        saveMembership(userId, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER, true);
        // user_roles には一切行を作らない（memberships 専属）。

        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(org.getId().intValue()))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"));
    }

    @Test
    @WithMockUser(username = "1001")
    @DisplayName("AC1-team: membership 専属（user_roles 行なし）のチームが /me/teams に含まれる")
    void ac1_team_membershipOnly_included() throws Exception {
        Long userId = 1001L;
        TeamEntity team = saveTeam("メンバーシップ専属チーム");
        saveMembership(userId, ScopeType.TEAM, team.getId(), RoleKind.MEMBER, true);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(team.getId().intValue()))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"));
    }

    // ───────────────────────────────────────────────────────────────────
    // AC2: user_roles ∩ memberships の両方に所属がある場合 1 件で返る
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "1002")
    @DisplayName("AC2-org: user_roles と memberships 両方に所属する組織は重複せず 1 件で返る")
    void ac2_org_bothSources_notDuplicated() throws Exception {
        Long userId = 1002L;
        OrganizationEntity org = saveOrg("両系統所属組織");
        saveOrgUserRole(userId, org.getId(), adminRoleId);
        saveMembership(userId, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER, true);

        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(org.getId().intValue()));
    }

    @Test
    @WithMockUser(username = "1002")
    @DisplayName("AC2-team: user_roles と memberships 両方に所属するチームは重複せず 1 件で返る")
    void ac2_team_bothSources_notDuplicated() throws Exception {
        Long userId = 1002L;
        TeamEntity team = saveTeam("両系統所属チーム");
        saveTeamUserRole(userId, team.getId(), adminRoleId);
        saveMembership(userId, ScopeType.TEAM, team.getId(), RoleKind.MEMBER, true);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(team.getId().intValue()));
    }

    // ───────────────────────────────────────────────────────────────────
    // AC3: role は priority 最強で解決される（ADMIN ∧ MEMBER → ADMIN）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "1003")
    @DisplayName("AC3-org: user_roles=ADMIN ∧ membership=MEMBER の組織 role は priority 最強 ADMIN")
    void ac3_org_adminBeatsMember() throws Exception {
        Long userId = 1003L;
        OrganizationEntity org = saveOrg("ADMIN兼MEMBER組織");
        saveOrgUserRole(userId, org.getId(), adminRoleId);
        saveMembership(userId, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER, true);

        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"));
    }

    @Test
    @WithMockUser(username = "1003")
    @DisplayName("AC3-team: user_roles=ADMIN ∧ membership=MEMBER のチーム role は priority 最強 ADMIN")
    void ac3_team_adminBeatsMember() throws Exception {
        Long userId = 1003L;
        TeamEntity team = saveTeam("ADMIN兼MEMBERチーム");
        saveTeamUserRole(userId, team.getId(), adminRoleId);
        saveMembership(userId, ScopeType.TEAM, team.getId(), RoleKind.MEMBER, true);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"));
    }

    // ───────────────────────────────────────────────────────────────────
    // AC4: SUPPORTER 専属（membership role_kind=SUPPORTER のみ）は role="SUPPORTER"
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "1004")
    @DisplayName("AC4-org: SUPPORTER 専属の組織は role=SUPPORTER で含まれる")
    void ac4_org_supporterOnly() throws Exception {
        Long userId = 1004L;
        OrganizationEntity org = saveOrg("サポーター専属組織");
        saveMembership(userId, ScopeType.ORGANIZATION, org.getId(), RoleKind.SUPPORTER, true);

        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(org.getId().intValue()))
                .andExpect(jsonPath("$.data[0].role").value("SUPPORTER"));
    }

    @Test
    @WithMockUser(username = "1004")
    @DisplayName("AC4-team: SUPPORTER 専属のチームは role=SUPPORTER で含まれる")
    void ac4_team_supporterOnly() throws Exception {
        Long userId = 1004L;
        TeamEntity team = saveTeam("サポーター専属チーム");
        saveMembership(userId, ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER, true);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(team.getId().intValue()))
                .andExpect(jsonPath("$.data[0].role").value("SUPPORTER"));
    }

    // ───────────────────────────────────────────────────────────────────
    // AC5: 退会済み（left_at IS NOT NULL）の membership は含まれない
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "1005")
    @DisplayName("AC5-org: 退会済み membership の組織は /me/organizations に含まれない")
    void ac5_org_leftMembership_excluded() throws Exception {
        Long userId = 1005L;
        OrganizationEntity org = saveOrg("退会済み組織");
        saveMembership(userId, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER, false);

        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "1005")
    @DisplayName("AC5-team: 退会済み membership のチームは /me/teams に含まれない")
    void ac5_team_leftMembership_excluded() throws Exception {
        Long userId = 1005L;
        TeamEntity team = saveTeam("退会済みチーム");
        saveMembership(userId, ScopeType.TEAM, team.getId(), RoleKind.MEMBER, false);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
    @ParameterizedTest
    @CsvSource({"ADMIN,false,true", "DEPUTY_ADMIN,false,true", "MEMBER,false,false",
            "ADMIN,true,true", "DEPUTY_ADMIN,true,true", "MEMBER,true,false"})
    @WithMockUser(username = "1010")
    @DisplayName("アーカイブ操作フラグは対象チーム割当に従いSYSTEM_ADMIN兼任で変化しない")
    void archiveCapability_matchesScopeRole(String scopeRole, boolean systemAdmin, boolean allowed) throws Exception {
        Long userId = 1010L;
        TeamEntity team = saveTeam("アーカイブ操作チーム");
        Long roleId = "ADMIN".equals(scopeRole) ? adminRoleId
                : "DEPUTY_ADMIN".equals(scopeRole) ? deputyRoleId : memberRoleId;
        saveTeamUserRole(userId, team.getId(), roleId);
        if (systemAdmin) {
            userRoleRepository.save(UserRoleEntity.builder().userId(userId).roleId(systemRoleId).build());
        }
        em.flush();
        assertThat(accessControlService.isAdminOrAbove(userId, team.getId(), "TEAM")).isEqualTo(allowed);

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value(systemAdmin ? "SYSTEM_ADMIN" : scopeRole))
                .andExpect(jsonPath("$.data[0].canManageArchive").value(allowed))
                .andExpect(jsonPath("$.data[0].isArchived").value(false));
    }

    @Test
    @WithMockUser(username = "1011")
    @DisplayName("アーカイブ済みチームはincludeArchived時だけ返し解除権限も返す")
    void archivedTeam_onlyReturnedWhenRequested() throws Exception {
        TeamEntity team = saveTeam("アーカイブ済みチーム");
        saveTeamUserRole(1011L, team.getId(), adminRoleId);
        team.archive();
        em.flush();

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/me/teams").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].isArchived").value(true))
                .andExpect(jsonPath("$.data[0].canManageArchive").value(true));
    }

    @Test
    @WithMockUser(username = "1012")
    @DisplayName("SYSTEM_ADMIN単独は他チームを一覧へ追加しない")
    void systemAdminAlone_hasNoTeamCapability() throws Exception {
        MembershipTestHelper.insertActiveUser(em, 1012L);
        userRoleRepository.save(UserRoleEntity.builder().userId(1012L).roleId(systemRoleId).build());
        saveTeam("未所属チーム");
        em.flush();
        mockMvc.perform(get("/api/v1/me/teams").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "1013")
    @DisplayName("停止ユーザーのチームADMIN割当はアーカイブ操作不可")
    void inactiveAdmin_hasNoArchiveCapability() throws Exception {
        TeamEntity team = saveTeam("停止利用者チーム");
        saveTeamUserRole(1013L, team.getId(), adminRoleId);
        em.find(UserEntity.class, 1013L).freeze();
        em.flush();
        assertThat(accessControlService.isAdminOrAbove(1013L, team.getId(), "TEAM")).isFalse();
        // WithMockUser は認証済みという前提のみ作る。操作可否は実 users 状態を使う。
        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].canManageArchive").value(false));
    }

    @Test
    @WithMockUser(username = "1014")
    @DisplayName("チーム件数を増やしてもアーカイブ操作可否のSQL数を増やさない")
    void archiveCapability_queryCountDoesNotGrowWithTeams() throws Exception {
        TeamEntity first = saveTeam("一括権限判定チーム");
        saveTeamUserRole(1014L, first.getId(), deputyRoleId);
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeOne = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/v1/me/teams"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
            long oneTeamQueries = statistics.getPrepareStatementCount() - beforeOne;
            for (int i = 0; i < 20; i++) {
                saveTeamUserRole(1014L, saveTeam("追加チーム").getId(), deputyRoleId);
            }
            em.flush();
            em.clear();
            long beforeMany = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/v1/me/teams"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(21)))
                    .andExpect(jsonPath("$.data[*].canManageArchive", hasSize(21)))
                    .andExpect(jsonPath("$.data[*].canManageArchive", everyItem(is(true))));
            long manyTeamQueries = statistics.getPrepareStatementCount() - beforeMany;
            assertThat(manyTeamQueries).as("1チームと21チームの一覧SQL数").isLessThanOrEqualTo(oneTeamQueries);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

}
