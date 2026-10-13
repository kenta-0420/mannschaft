package com.mannschaft.app.jobmatching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
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
 * CMP-260821-1027: 契約詳細GETだけの私的子ID本文契約。
 * 実JWT・filter・Service・MySQL・Mapper・GHEを通す。本人の役割を増減させず、
 * write/QR/公開求人/親削除の設計には触れない。fixtureはRepository経由の独立TXで確定する。
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class JobContractReadScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private JobPostingRepository postings;
    @Autowired private JobApplicationRepository applications;
    @Autowired private JobContractRepository contracts;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @PersistenceContext private EntityManager em;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> postingIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();
    private final List<Long> contractIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Actor> nonParticipants = new ArrayList<>();
    private Long team, requester, worker;

    @BeforeEach
    void Repositoryで自所有fixtureを確定する() {
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
            nonParticipants.add(new Actor("所属なし", user(), List.of("USER")));
            for (Long scope : List.of(team, foreignTeam)) {
                Long member = user();
                memberships.save(MembershipEntity.builder().userId(member).scopeType(ScopeType.TEAM)
                        .scopeId(scope).roleKind(RoleKind.MEMBER).build());
                nonParticipants.add(new Actor("MEMBER:" + scope, member, List.of("USER")));
                for (String name : List.of("ADMIN", "DEPUTY_ADMIN")) {
                    for (boolean membership : List.of(true, false)) {
                        Long id = user();
                        if (membership) memberships.save(MembershipEntity.builder().userId(id).scopeType(ScopeType.TEAM)
                                .scopeId(scope).roleKind(RoleKind.MEMBER).build());
                        assignRole(id, name, scope);
                        nonParticipants.add(new Actor(name + ":" + scope + ":membership=" + membership, id, List.of("USER", name)));
                    }
                }
            }
            Long system = user();
            assignRole(system, "SYSTEM_ADMIN", null);
            nonParticipants.add(new Actor("SYSTEM_ADMIN", system, List.of("USER", "SYSTEM_ADMIN")));
            assertThat(contracts.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有行を削除して共有mockと認証状態を復元する() {
        try {
            tx(() -> {
                contracts.deleteAllById(contractIds);
                em.flush();
                applications.deleteAllById(applicationIds);
                em.flush();
                postings.deleteAllById(postingIds);
                em.flush();
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                // 共有の既存roleは保持し、このfixtureが新作したroleだけを削除する。
                roles.deleteAllById(createdRoleIds);
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
    void 全状態の非当事者は不在と404本文まで一致する() throws Exception {
        for (JobContractStatus state : JobContractStatus.values()) {
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
    void 全状態のRequesterとWorkerは役割所属なしでも本人200を維持する() throws Exception {
        tx(() -> {
            for (Long id : List.of(requester, worker)) {
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM memberships WHERE user_id=:id")
                        .setParameter("id", id).getSingleResult()).longValue()).isZero();
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM user_roles WHERE user_id=:id")
                        .setParameter("id", id).getSingleResult()).longValue()).isZero();
            }
            return null;
        });
        for (JobContractStatus state : JobContractStatus.values()) {
            Long id = fixture(state);
            Snapshot before = snapshot();
            try {
                for (Long actor : List.of(requester, worker)) {
                    MvcResult result = call(tokens.issueAccessToken(actor, List.of("USER")), id.toString());
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    JsonNode data = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
                    assertThat(data.path("id").asLong()).isEqualTo(id);
                    assertThat(data.path("requesterUserId").asLong()).isEqualTo(requester);
                    assertThat(data.path("workerUserId").asLong()).isEqualTo(worker);
                    assertThat(data.path("status").asText()).isEqualTo(state.name());
                    assertThat(data.path("baseRewardJpy").asInt()).isEqualTo(3000);
                    assertThat(data.path("lastRejectionReason").asText()).isEqualTo("私的な差戻し理由");
                }
            } finally { assertThat(snapshot()).isEqualTo(before); }
        }
    }

    @Test
    void 不在数値境界と不正形式と未認証の診断を維持する() throws Exception {
        Long id = fixture(JobContractStatus.MATCHED);
        Snapshot before = snapshot();
        try {
            String token = tokens.issueAccessToken(worker, List.of("USER"));
            for (long missing : List.of(0L, -1L, MISSING)) {
                assertThat(tx(() -> contracts.existsById(missing))).isFalse();
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
        var request = get("/api/v1/contracts/" + id).header("Accept-Language", "ja");
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
        expected.putObject("error").put("code", "JOB_CONTRACT_NOT_FOUND")
                .put("message", "契約が見つかりません").putArray("fieldErrors");
        assertThat(json(response)).isEqualTo(expected);
    }
    private Long fixture(JobContractStatus state) {
        return tx(() -> {
            LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
            JobPostingEntity posting = postings.save(JobPostingEntity.builder().teamId(team).createdByUserId(requester)
                    .title("契約詳細GET試練").description("私的契約の親求人").workLocationType(WorkLocationType.ONSITE)
                    .workAddress("試練会場").workStartAt(start).workEndAt(start.plusHours(4))
                    .rewardType(RewardType.LUMP_SUM).baseRewardJpy(3000).capacity(1).applicationDeadlineAt(start.minusDays(1))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS).status(JobPostingStatus.OPEN).build());
            postingIds.add(posting.getId());
            JobApplicationEntity application = applications.save(JobApplicationEntity.builder().jobPostingId(posting.getId())
                    .applicantUserId(worker).selfPr("私的な自己PR").status(JobApplicationStatus.ACCEPTED).appliedAt(start.minusDays(2)).build());
            applicationIds.add(application.getId());
            JobContractEntity contract = contracts.save(JobContractEntity.builder().jobPostingId(posting.getId())
                    .jobApplicationId(application.getId()).requesterUserId(requester).workerUserId(worker)
                    .baseRewardJpy(3000).workStartAt(start).workEndAt(start.plusHours(4)).status(state).matchedAt(start.minusDays(1))
                    .lastRejectionReason("私的な差戻し理由").build());
            contractIds.add(contract.getId());
            em.flush();
            assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(state);
            assertThat(postings.existsById(posting.getId())).isTrue();
            assertThat(applications.existsById(application.getId())).isTrue();
            return contract.getId();
        });
    }
    private Long user() {
        Long id = users.save(UserEntity.builder().email("contract-get-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("契約").displayName("契約GET").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }
    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("jc-" + UUID.randomUUID().toString().substring(0, 8))
                .name("契約GET").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
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
                assertThat(selected).as("自所有の%s行が全件実在する", table).hasSize(postingIds.size());
                rows.add(Arrays.deepToString(selected.toArray()));
            }
            for (String table : List.of("users", "user_roles", "memberships")) {
                String column = table.equals("users") ? "id" : "user_id";
                rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + column + " IN (:ids) ORDER BY id")
                        .setParameter("ids", userIds).getResultList().toArray()));
            }
            rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM teams WHERE id IN (:ids) ORDER BY id")
                    .setParameter("ids", teamIds).getResultList().toArray()));
            if (createdRoleIds.isEmpty()) rows.add("[]");
            else {
                List<?> ownedRoles = em.createNativeQuery("SELECT * FROM roles WHERE id IN (:ids) ORDER BY id")
                        .setParameter("ids", createdRoleIds).getResultList();
                assertThat(ownedRoles).hasSize(createdRoleIds.size());
                rows.add(Arrays.deepToString(ownedRoles.toArray()));
            }
            return new Snapshot(List.copyOf(rows));
        });
    }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private record Actor(String name, Long id, List<String> claims) { }
    private record Snapshot(List<String> rows) { }
}
