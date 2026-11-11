package com.mannschaft.app.proxyvote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作成と画面表示用の判定を、実フィルタと実 MySQL 上の同じスコープ権限で照合する。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("投票セッション作成のスコープ認可")
class ProxyVoteCreationScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long ADMIN = 8_083_101L;
    private static final long DEPUTY = 8_083_102L;
    private static final long SYSTEM_ADMIN = 8_083_103L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeamRepository teamRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private PermissionGroupService permissionGroupService;
    @Autowired private ProxyVoteSessionRepository sessionRepository;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN);
        MembershipTestHelper.insertActiveUser(em, DEPUTY);
        MembershipTestHelper.insertActiveUser(em, SYSTEM_ADMIN);
        MembershipTestHelper.insertUserRole(em, SYSTEM_ADMIN, "SYSTEM_ADMIN", null, null);
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @DisplayName("未付与のDEPUTY_ADMINとSYSTEM_ADMIN単独は表示不可・403・保存なし")
    void 作成権限がないときは保存しない(ScopeType type) throws Exception {
        VoteScope scope = createScope(type, "a");
        assignRole(DEPUTY, scope, "DEPUTY_ADMIN");
        long before = sessionRepository.count();

        expectCapability(scope, DEPUTY, false);
        createVote(scope, DEPUTY).andExpect(status().isForbidden());
        expectCapability(scope, SYSTEM_ADMIN, false);
        createVote(scope, SYSTEM_ADMIN).andExpect(status().isForbidden());

        assertThat(sessionRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @DisplayName("同スコープへのMANAGE_VOTES付与で表示・作成可能。SYSTEM_ADMIN兼任でも有効")
    void 付与された対象スコープにだけ作成できる(ScopeType type) throws Exception {
        VoteScope scope = createScope(type, "a");
        VoteScope otherScope = createScope(type, "b");
        assignRole(ADMIN, scope, "ADMIN");
        assignRole(DEPUTY, scope, "DEPUTY_ADMIN");
        assignRole(DEPUTY, otherScope, "DEPUTY_ADMIN");
        grantManageVotes(scope);
        long before = sessionRepository.count();

        expectCapability(scope, ADMIN, true);
        createVote(scope, ADMIN).andExpect(status().isCreated());
        expectCapability(scope, DEPUTY, true);
        MembershipTestHelper.insertUserRole(em, DEPUTY, "SYSTEM_ADMIN", null, null);
        expectCapability(scope, DEPUTY, true).andExpect(jsonPath("$.data.roleName").value("SYSTEM_ADMIN"));
        createVote(scope, DEPUTY).andExpect(status().isCreated());
        assertThat(sessionRepository.count()).isEqualTo(before + 2);

        expectCapability(otherScope, DEPUTY, false);
        createVote(otherScope, DEPUTY).andExpect(status().isForbidden());
        assertThat(sessionRepository.count()).isEqualTo(before + 2);
    }

    private void grantManageVotes(VoteScope scope) {
        PermissionEntity permission = permissionRepository.findByNameIn(List.of("MANAGE_VOTES")).stream()
                .findFirst().orElseGet(() -> permissionRepository.save(PermissionEntity.builder()
                        .name("MANAGE_VOTES").displayName("投票セッション作成")
                        .scope(PermissionEntity.Scope.ORGANIZATION).build()));
        Long groupId = permissionGroupService.createPermissionGroup(scope.id(), scope.type().name(),
                new PermissionGroupRequest("投票担当", "DEPUTY_ADMIN", List.of(permission.getId())), ADMIN)
                .getData().getId();
        permissionGroupService.assignUserPermissionGroups(DEPUTY, scope.id(), scope.type().name(),
                new UserPermissionGroupAssignRequest(List.of(groupId)), ADMIN);
    }

    private void assignRole(long userId, VoteScope scope, String role) {
        MembershipTestHelper.insertMembership(em, userId, scope.type(), scope.id(), RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, role,
                scope.type() == ScopeType.TEAM ? scope.id() : null,
                scope.type() == ScopeType.ORGANIZATION ? scope.id() : null);
    }

    private VoteScope createScope(ScopeType type, String suffix) {
        String slug = "pv-create-" + suffix;
        if (type == ScopeType.TEAM) {
            TeamEntity team = teamRepository.save(TeamEntity.builder().name("投票チーム " + suffix).slug(slug)
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).memberCount(0L).build());
            return new VoteScope(type, team.getId(), slug);
        }
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .name("投票組織 " + suffix).slug(slug).orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
        return new VoteScope(type, org.getId(), slug);
    }

    private ResultActions expectCapability(VoteScope scope, long userId, boolean expected) throws Exception {
        String collection = scope.type() == ScopeType.TEAM ? "teams" : "organizations";
        return mockMvc.perform(get("/api/v1/" + collection + "/{slug}/me/permissions", scope.slug())
                        .with(asUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canCreateVoteSession").value(expected));
    }

    private ResultActions createVote(VoteScope scope, long userId) throws Exception {
        String scopeIdField = scope.type() == ScopeType.TEAM ? "teamId" : "organizationId";
        String body = objectMapper.writeValueAsString(Map.of(
                "scopeType", scope.type().name(), scopeIdField, scope.id(),
                "resolutionMode", "WRITTEN", "title", "書面決議"));
        return mockMvc.perform(post("/api/v1/proxy-votes").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor asUser(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(Long.toString(userId), "unused", List.of()));
    }

    private record VoteScope(ScopeType type, Long id, String slug) {}
}
