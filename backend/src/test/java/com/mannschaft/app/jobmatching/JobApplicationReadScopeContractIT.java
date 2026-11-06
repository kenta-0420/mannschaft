package com.mannschaft.app.jobmatching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-260821-1027: 生存親を持つ応募詳細GETの私的子ID本文契約。
 * 実JWT/filter/Service/MySQL/Mapper/GHEを通す。本人と既存採否権限者の許可を維持する。
 * 公開求人/list/write/QR、親欠損・論理削除の仕様は対象外。fixtureはRepository経由のみ。
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class JobApplicationReadScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private JobPostingRepository postings;
    @Autowired private JobApplicationRepository applications;
    @Autowired private PermissionRepository permissions;
    @Autowired private PermissionGroupRepository groups;
    @Autowired private PermissionGroupPermissionRepository groupPermissions;
    @Autowired private UserPermissionGroupRepository userGroups;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @PersistenceContext private EntityManager em;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> postingIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> createdPermissionIds = new ArrayList<>();
    private final List<Actor> readers = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Actor> nonParticipants = new ArrayList<>();
    private Long team, requester, worker;

    @BeforeEach
    void Repositoryで自己所有fixtureと実権限グループを確定する() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        tx(() -> {
            team = team();
            Long foreignTeam = team();
            requester = user();
            worker = user();
            readers.add(new Actor("応募者本人・役割所属なし", worker, List.of("USER")));
            nonParticipants.add(new Actor("Requester本人・役割所属なし", requester, List.of("USER")));
            nonParticipants.add(new Actor("所属なし", user(), List.of("USER")));
            for (boolean membership : List.of(true, false)) {
                Long admin = user();
                assignRole(admin, "ADMIN", team);
                if (membership) member(admin, team);
                readers.add(new Actor("TEAM ADMIN:membership=" + membership, admin, List.of("USER", "ADMIN")));
            }
            Long deputy = user();
            assignRole(deputy, "DEPUTY_ADMIN", team);
            grantJobsManage(deputy, team, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
            readers.add(new Actor("DEPUTY_ADMIN:jobs.manage", deputy, List.of("USER", "DEPUTY_ADMIN")));
            Long authorizedMember = user();
            member(authorizedMember, team);
            grantJobsManage(authorizedMember, team, PermissionGroupEntity.TargetRole.MEMBER);
            readers.add(new Actor("MEMBER:jobs.manage", authorizedMember, List.of("USER")));
            Long plainMember = user();
            member(plainMember, team);
            nonParticipants.add(new Actor("MEMBER:権限なし", plainMember, List.of("USER")));
            Long plainDeputy = user();
            assignRole(plainDeputy, "DEPUTY_ADMIN", team);
            nonParticipants.add(new Actor("DEPUTY_ADMIN:権限なし", plainDeputy, List.of("USER", "DEPUTY_ADMIN")));
            Long foreignAdmin = user();
            assignRole(foreignAdmin, "ADMIN", foreignTeam);
            nonParticipants.add(new Actor("foreign ADMIN", foreignAdmin, List.of("USER", "ADMIN")));
            Long foreignDeputy = user();
            assignRole(foreignDeputy, "DEPUTY_ADMIN", foreignTeam);
            grantJobsManage(foreignDeputy, foreignTeam, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
            nonParticipants.add(new Actor("foreign jobs.manage", foreignDeputy, List.of("USER", "DEPUTY_ADMIN")));
            Long system = user();
            assignRole(system, "SYSTEM_ADMIN", null);
            nonParticipants.add(new Actor("SYSTEM_ADMIN単独", system, List.of("USER", "SYSTEM_ADMIN")));
            em.flush();
            assertThat(applications.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自己所有行だけをFK順で削除して共有mockと認証状態を復元する() {
        try {
            tx(() -> {
                applications.deleteAllById(applicationIds);
                em.flush();
                postings.deleteAllById(postingIds);
                em.flush();
                for (Long id : groupIds) {
                    userGroups.deleteByGroupId(id);
                    groupPermissions.deleteByGroupId(id);
                }
                em.flush();
                groups.deleteAllById(groupIds);
                em.flush();
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                roles.deleteAllById(createdRoleIds);
                permissions.deleteAllById(createdPermissionIds);
                teams.deleteAllById(teamIds);
                em.flush();
                return null;
            });
        } finally {
            try { reset(redisTemplate); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    @Test
    void 全状態の非閲覧者は不在と404本文まで一致する() throws Exception {
        for (JobApplicationStatus state : JobApplicationStatus.values()) {
            Long id = fixture(state);
            Snapshot before = snapshot();
            try {
                for (Actor actor : nonParticipants) {
                    String token = tokens.issueAccessToken(actor.id(), actor.claims());
                    MvcResult missing = call(token, Long.toString(MISSING));
                    MvcResult existing = call(token, id.toString());
                    assertNotFound(missing);
                    assertNotFound(existing);
                    assertThat(existing.getResponse().getContentType()).isEqualTo(missing.getResponse().getContentType());
                    assertThat(json(existing)).as("%s / %s", actor.name(), state).isEqualTo(json(missing));
                }
            } finally { assertThat(snapshot()).isEqualTo(before); }
        }
    }

    @Test
    void 全状態の応募者本人と現採否権限者の200を維持する() throws Exception {
        tx(() -> {
            for (Long id : List.of(requester, worker)) {
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM memberships WHERE user_id=:id")
                        .setParameter("id", id).getSingleResult()).longValue()).isZero();
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM user_roles WHERE user_id=:id")
                        .setParameter("id", id).getSingleResult()).longValue()).isZero();
            }
            return null;
        });
        for (JobApplicationStatus state : JobApplicationStatus.values()) {
            Long id = fixture(state);
            Snapshot before = snapshot();
            try {
                for (Actor actor : readers) {
                    MvcResult result = call(tokens.issueAccessToken(actor.id(), actor.claims()), id.toString());
                    assertThat(result.getResponse().getStatus()).as("%s / %s", actor.name(), state).isEqualTo(200);
                    JsonNode data = json(result).path("data");
                    assertThat(data.path("id").asLong()).isEqualTo(id);
                    assertThat(data.path("applicantUserId").asLong()).isEqualTo(worker);
                    assertThat(data.path("status").asText()).isEqualTo(state.name());
                    assertThat(data.path("selfPr").asText()).isEqualTo("私的な自己PR");
                    assertThat(data.path("jobPostingId").asLong()).isEqualTo(tx(() -> applications.findById(id).orElseThrow().getJobPostingId()));
                }
            } finally { assertThat(snapshot()).isEqualTo(before); }
        }
    }

    @Test
    void 不在数値境界と不正形式と未認証の診断を維持する() throws Exception {
        Long id = fixture(JobApplicationStatus.APPLIED);
        Snapshot before = snapshot();
        try {
            String token = tokens.issueAccessToken(worker, List.of("USER"));
            for (long missing : List.of(0L, -1L, MISSING)) {
                assertThat(tx(() -> applications.existsById(missing))).isFalse();
                assertNotFound(call(token, Long.toString(missing)));
            }
            for (String invalid : List.of("not-a-long", "9223372036854775808")) {
                assertThat(call(token, invalid).getResponse().getStatus()).isEqualTo(400);
            }
            for (String target : List.of(id.toString(), Long.toString(MISSING))) {
                assertThat(call(null, target).getResponse().getStatus()).isEqualTo(401);
            }
        } finally { assertThat(snapshot()).isEqualTo(before); }
    }

    private MvcResult call(String token, String id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var request = get("/api/v1/applications/" + id).header("Accept-Language", "ja");
        if (token != null) request.header("Authorization", "Bearer " + token);
        try { return mvc.perform(request).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private JsonNode json(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private void assertNotFound(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(404);
        MediaType contentType = MediaType.parseMediaType(response.getResponse().getContentType());
        assertThat(contentType.getType()).isEqualTo("application");
        assertThat(contentType.getSubtype()).isEqualTo("json");
        var expected = mapper.createObjectNode();
        expected.putObject("error").put("code", "JOB_APPLICATION_NOT_FOUND")
                .put("message", "応募が見つかりません").putArray("fieldErrors");
        assertThat(json(response)).isEqualTo(expected);
    }
    private Long fixture(JobApplicationStatus state) {
        return tx(() -> {
            LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
            JobPostingEntity posting = postings.save(JobPostingEntity.builder().teamId(team).createdByUserId(requester)
                    .title("応募詳細GET試練").description("応募の親求人").workLocationType(WorkLocationType.ONSITE)
                    .workAddress("試練会場").workStartAt(start).workEndAt(start.plusHours(4))
                    .rewardType(RewardType.LUMP_SUM).baseRewardJpy(3000).capacity(1).applicationDeadlineAt(start.minusDays(1))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS).status(JobPostingStatus.OPEN).build());
            postingIds.add(posting.getId());
            JobApplicationEntity application = applications.save(JobApplicationEntity.builder().jobPostingId(posting.getId())
                    .applicantUserId(worker).selfPr("私的な自己PR").status(state).appliedAt(start.minusDays(2))
                    .decidedAt(state == JobApplicationStatus.APPLIED ? null : start.minusDays(1))
                    .decidedByUserId(state == JobApplicationStatus.ACCEPTED || state == JobApplicationStatus.REJECTED ? requester : null).build());
            applicationIds.add(application.getId());
            em.flush();
            assertThat(applications.findById(application.getId()).orElseThrow().getStatus()).isEqualTo(state);
            assertThat(postings.existsById(posting.getId())).isTrue();
            return application.getId();
        });
    }
    private void member(Long user, Long scope) {
        memberships.save(MembershipEntity.builder().userId(user).scopeType(ScopeType.TEAM)
                .scopeId(scope).roleKind(RoleKind.MEMBER).build());
    }
    private void grantJobsManage(Long user, Long scope, PermissionGroupEntity.TargetRole target) {
        List<PermissionEntity> found = permissions.findByNameIn(List.of("jobs.manage"));
        assertThat(found).hasSizeLessThanOrEqualTo(1);
        PermissionEntity permission;
        if (found.isEmpty()) {
            permission = permissions.save(PermissionEntity.builder().name("jobs.manage").displayName("求人管理")
                    .scope(PermissionEntity.Scope.TEAM).build());
            createdPermissionIds.add(permission.getId());
        } else permission = found.get(0);
        PermissionGroupEntity group = groups.save(PermissionGroupEntity.builder().teamId(scope).targetRole(target)
                .name("応募GET-" + UUID.randomUUID()).createdBy(requester).build());
        groupIds.add(group.getId());
        groupPermissions.save(PermissionGroupPermissionEntity.builder().groupId(group.getId()).permissionId(permission.getId()).build());
        userGroups.save(UserPermissionGroupEntity.builder().userId(user).groupId(group.getId()).assignedBy(requester).build());
    }
    private Long user() {
        Long id = users.save(UserEntity.builder().email("application-get-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("応募").displayName("応募GET").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }
    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("ja-" + UUID.randomUUID().toString().substring(0, 8))
                .name("応募GET").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
        teamIds.add(id); return id;
    }
    private void assignRole(Long user, String name, Long scope) {
        RoleEntity role = roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder().name(name).displayName(name)
                    .priority(RolePriority.priority(name)).isSystem(name.equals("SYSTEM_ADMIN")).build());
            createdRoleIds.add(created.getId());
            return created;
        });
        userRoles.save(UserRoleEntity.builder().userId(user).roleId(role.getId()).teamId(scope).build());
    }
    private Snapshot snapshot() {
        return tx(() -> {
            em.flush(); em.clear();
            List<String> rows = new ArrayList<>();
            for (String table : List.of("job_contracts", "job_applications", "job_postings")) {
                String column = table.equals("job_postings") ? "id" : "job_posting_id";
                List<?> selected = em.createNativeQuery("SELECT * FROM " + table + " WHERE " + column + " IN (:ids) ORDER BY id")
                        .setParameter("ids", postingIds).getResultList();
                assertThat(selected).as("自己所有の%s全列と子契約未生成を固定する", table)
                        .hasSize(table.equals("job_contracts") ? 0 : postingIds.size());
                rows.add(Arrays.deepToString(selected.toArray()));
            }
            for (String table : List.of("users", "user_roles", "memberships", "user_permission_groups")) {
                String column = table.equals("users") ? "id" : "user_id";
                rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + column + " IN (:ids) ORDER BY id")
                        .setParameter("ids", userIds).getResultList().toArray()));
            }
            rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM teams WHERE id IN (:ids) ORDER BY id")
                    .setParameter("ids", teamIds).getResultList().toArray()));
            for (String table : List.of("permission_groups", "permission_group_permissions")) {
                String column = table.equals("permission_groups") ? "id" : "group_id";
                List<?> selected = em.createNativeQuery("SELECT * FROM " + table + " WHERE " + column + " IN (:ids) ORDER BY id")
                        .setParameter("ids", groupIds).getResultList();
                assertThat(selected).hasSize(groupIds.size());
                rows.add(Arrays.deepToString(selected.toArray()));
            }
            for (var owned : List.of(new OwnedTable("roles", createdRoleIds), new OwnedTable("permissions", createdPermissionIds))) {
                if (owned.ids().isEmpty()) rows.add("[]");
                else {
                    List<?> selected = em.createNativeQuery("SELECT * FROM " + owned.table() + " WHERE id IN (:ids) ORDER BY id")
                            .setParameter("ids", owned.ids()).getResultList();
                    assertThat(selected).hasSize(owned.ids().size());
                    rows.add(Arrays.deepToString(selected.toArray()));
                }
            }
            return new Snapshot(List.copyOf(rows));
        });
    }
    private record OwnedTable(String table, List<Long> ids) { }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private record Actor(String name, Long id, List<String> claims) { }
    private record Snapshot(List<String> rows) { }
}
