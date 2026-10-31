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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260821-1027 T1: 契約の書込（完了報告・承認・差戻・解約）における存在オラクルの本文正規化（試練）。
 *
 * <p>非当事者が実在契約 ID に書込を試みた応答と、不在 ID の応答が status・error.code・message まで
 * 完全一致することを契約とする。修正前は越境が {@code JOB_PERMISSION_DENIED}、不在が
 * {@code JOB_CONTRACT_NOT_FOUND} で本文が割れており RED になる。</p>
 *
 * <p>金型: {@link JobContractReadScopeContractIT}（#3814。実JWT・filter・Service・MySQL・GHE を通し、
 * fixture は Repository 経由の独立TXで確定する。acceptApplication の GET_LOCK を含む系と同じく
 * テストTXを張らない）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260821-1027 T1 契約書込の越境/不在 本文一致契約")
class JobContractWriteBodyParityContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;

    /** 書込操作。必要な事前状態と、正当な実行者・遷移後状態を持つ。 */
    private enum Op {
        REPORT("report-completion", "{\"message\":\"完了しました\"}", JobContractStatus.MATCHED, JobContractStatus.COMPLETION_REPORTED),
        APPROVE("approve-completion", "{}", JobContractStatus.COMPLETION_REPORTED, JobContractStatus.COMPLETED),
        REJECT("reject-completion", "{\"reason\":\"やり直し\"}", JobContractStatus.COMPLETION_REPORTED, JobContractStatus.MATCHED),
        CANCEL("cancel", "{\"reason\":\"都合\"}", JobContractStatus.MATCHED, JobContractStatus.CANCELLED);

        final String path;
        final String body;
        final JobContractStatus before;
        final JobContractStatus after;

        Op(String path, String body, JobContractStatus before, JobContractStatus after) {
            this.path = path; this.body = body; this.before = before; this.after = after;
        }
    }

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
            nonParticipants.add(new Actor("所属なし", user()));
            Long member = user();
            memberships.save(MembershipEntity.builder().userId(member).scopeType(ScopeType.TEAM)
                    .scopeId(team).roleKind(RoleKind.MEMBER).build());
            nonParticipants.add(new Actor("同TEAMのMEMBER", member));
            for (Long scope : List.of(team, foreignTeam)) {
                Long admin = user();
                memberships.save(MembershipEntity.builder().userId(admin).scopeType(ScopeType.TEAM)
                        .scopeId(scope).roleKind(RoleKind.MEMBER).build());
                assignRole(admin, "ADMIN", scope);
                nonParticipants.add(new Actor(scope.equals(team) ? "同TEAMのADMIN" : "他TEAMのADMIN", admin));
            }
            assertThat(contracts.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有行を削除して共有mockと認証状態を復元する() {
        try {
            tx(() -> {
                if (!postingIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM job_contracts WHERE job_posting_id IN (:ids)").setParameter("ids", postingIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM job_applications WHERE job_posting_id IN (:ids)").setParameter("ids", postingIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM job_postings WHERE id IN (:ids)").setParameter("ids", postingIds).executeUpdate();
                }
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
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
    @DisplayName("AC-1027-T1-1: 非当事者の越境書込と不在IDの応答が status・error.code・message まで一致する")
    void AC_1027_T1_1_越境と不在の本文一致() throws Exception {
        List<String> diffs = new ArrayList<>();
        for (Op op : Op.values()) {
            Long id = fixture(op.before);
            for (Actor actor : nonParticipants) {
                String token = tokens.issueAccessToken(actor.id(), List.of("USER"));
                MvcResult missing = call(op, token, MISSING);
                MvcResult existing = call(op, token, id);
                assertThat(missing.getResponse().getStatus()).as("%s 不在", op).isEqualTo(404);
                if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                        || !json(existing).equals(json(missing))) {
                    diffs.add(op + "/" + actor.name() + ": 越境=" + existing.getResponse().getStatus() + " " + json(existing)
                            + " 不在=" + missing.getResponse().getStatus() + " " + json(missing));
                }
            }
        }
        assertThat(diffs).as("越境と不在で応答が割れている操作").isEmpty();
    }

    @Test
    @DisplayName("AC-1027-T1-2: 越境書込の後も契約・応募・求人の行は不変（状態・差戻回数・版）")
    void AC_1027_T1_2_越境書込はDB不変() throws Exception {
        for (Op op : Op.values()) {
            Long id = fixture(op.before);
            Snapshot before = snapshot();
            for (Actor actor : nonParticipants) {
                call(op, tokens.issueAccessToken(actor.id(), List.of("USER")), id);
            }
            assertThat(snapshot()).as("%s", op).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("AC-1027-T1-3: 当事者の正常系（完了報告=Worker・承認/差戻=Requester・解約=双方）は200を維持する")
    void AC_1027_T1_3_当事者の正常系() throws Exception {
        for (Op op : Op.values()) {
            List<Long> actors = switch (op) {
                case REPORT -> List.of(worker);
                case APPROVE, REJECT -> List.of(requester);
                case CANCEL -> List.of(requester, worker);
            };
            for (Long actor : actors) {
                Long id = fixture(op.before);
                MvcResult result = call(op, tokens.issueAccessToken(actor, List.of("USER")), id);
                assertThat(result.getResponse().getStatus()).as("%s by %s: %s", op, actor,
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
                JobContractEntity after = tx(() -> contracts.findById(id).orElseThrow());
                assertThat(after.getStatus()).as("%s", op).isEqualTo(op.after);
                if (op == Op.REJECT) assertThat(after.getRejectionCount()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("AC-1027-T1-4: 未認証は実在・不在とも401でDB不変")
    void AC_1027_T1_4_未認証は401() throws Exception {
        for (Op op : Op.values()) {
            Long id = fixture(op.before);
            Snapshot before = snapshot();
            for (long target : List.of(id, MISSING)) {
                assertThat(call(op, null, target).getResponse().getStatus()).as("%s", op).isEqualTo(401);
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    private MvcResult call(Op op, String token, long id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var request = post("/api/v1/contracts/" + id + "/" + op.path).header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON).content(op.body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        try { return mvc.perform(request).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private JsonNode json(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Long fixture(JobContractStatus state) {
        return tx(() -> {
            LocalDateTime start = LocalDateTime.now().plusDays(10).withNano(0);
            JobPostingEntity posting = postings.save(JobPostingEntity.builder().teamId(team).createdByUserId(requester)
                    .title("契約書込試練").description("越境書込の親求人").workLocationType(WorkLocationType.ONSITE)
                    .workAddress("試練会場").workStartAt(start).workEndAt(start.plusHours(4))
                    .rewardType(RewardType.LUMP_SUM).baseRewardJpy(3000).capacity(1).applicationDeadlineAt(start.minusDays(1))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS).status(JobPostingStatus.OPEN).build());
            postingIds.add(posting.getId());
            JobApplicationEntity application = applications.save(JobApplicationEntity.builder().jobPostingId(posting.getId())
                    .applicantUserId(worker).selfPr("自己PR").status(JobApplicationStatus.ACCEPTED).appliedAt(start.minusDays(2)).build());
            JobContractEntity contract = contracts.save(JobContractEntity.builder().jobPostingId(posting.getId())
                    .jobApplicationId(application.getId()).requesterUserId(requester).workerUserId(worker)
                    .baseRewardJpy(3000).workStartAt(start).workEndAt(start.plusHours(4)).status(state).matchedAt(start.minusDays(1))
                    .build());
            em.flush();
            return contract.getId();
        });
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("contract-write-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("契約").displayName("契約書込").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }

    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("jw-" + UUID.randomUUID().toString().substring(0, 8))
                .name("契約書込").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
        teamIds.add(id); return id;
    }

    private void assignRole(Long user, String name, Long scope) {
        RoleEntity role = roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder().name(name).displayName(name)
                    .priority(RolePriority.priority(name)).isSystem(false).build());
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
                rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + column
                        + " IN (:ids) ORDER BY id").setParameter("ids", postingIds).getResultList().toArray()));
            }
            return new Snapshot(List.copyOf(rows));
        });
    }

    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private record Actor(String name, Long id) { }
    private record Snapshot(List<String> rows) { }
}
