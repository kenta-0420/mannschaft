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
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-260821-1027 T2: 求人・応募・QR の書込（および非公開の応募一覧）における存在オラクルの本文正規化（試練）。
 *
 * <p>権限外の者が実在 ID に操作した応答と不在 ID の応答が、status・error.code・message まで完全一致することを
 * 契約とする。修正前は権限外が {@code JOB_PERMISSION_DENIED}、不在が {@code JOB_NOT_FOUND} /
 * {@code JOB_APPLICATION_NOT_FOUND} / {@code JOB_CONTRACT_NOT_FOUND} で本文が割れており RED になる。
 * 求人の閲覧（GET /jobs/{id}）は公開情報のため対象外。</p>
 *
 * <p>金型: {@link JobContractReadScopeContractIT}（#3814）/ {@code JobApplicationReadScopeContractIT}（#3818）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260821-1027 T2 求人/応募/QR書込の越境/不在 本文一致契約")
class JobPostingApplicationWriteBodyParityContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;

    /** 対象 ID の種別。 */
    private enum Target { POSTING, APPLICATION, CONTRACT }

    /** 対象操作。path の {id} に実在 ID または不在 ID を差し込む。 */
    private enum Op {
        POSTING_UPDATE(HttpMethod.PATCH, "/api/v1/jobs/{id}", "{\"title\":\"改題\"}", Target.POSTING, JobPostingStatus.DRAFT, 200),
        POSTING_PUBLISH(HttpMethod.POST, "/api/v1/jobs/{id}/publish", null, Target.POSTING, JobPostingStatus.DRAFT, 200),
        POSTING_CLOSE(HttpMethod.POST, "/api/v1/jobs/{id}/close", null, Target.POSTING, JobPostingStatus.OPEN, 200),
        POSTING_CANCEL(HttpMethod.POST, "/api/v1/jobs/{id}/cancel", null, Target.POSTING, JobPostingStatus.OPEN, 200),
        POSTING_DELETE(HttpMethod.DELETE, "/api/v1/jobs/{id}", null, Target.POSTING, JobPostingStatus.OPEN, 204),
        APPLICATION_LIST(HttpMethod.GET, "/api/v1/jobs/{id}/applications", null, Target.POSTING, JobPostingStatus.OPEN, 200),
        APPLICATION_WITHDRAW(HttpMethod.POST, "/api/v1/applications/{id}/withdraw", null, Target.APPLICATION, JobPostingStatus.OPEN, 200),
        APPLICATION_REJECT(HttpMethod.POST, "/api/v1/applications/{id}/reject", "{}", Target.APPLICATION, JobPostingStatus.OPEN, 200),
        APPLICATION_ACCEPT(HttpMethod.POST, "/api/v1/applications/{id}/accept", null, Target.APPLICATION, JobPostingStatus.OPEN, 200),
        QR_ISSUE(HttpMethod.POST, "/api/v1/contracts/{id}/qr-tokens", "{\"type\":\"IN\"}", Target.CONTRACT, JobPostingStatus.OPEN, 201),
        QR_CURRENT(HttpMethod.GET, "/api/v1/contracts/{id}/qr-tokens/current?type=IN", null, Target.CONTRACT, JobPostingStatus.OPEN, 204);

        final HttpMethod method;
        final String path;
        final String body;
        final Target target;
        final JobPostingStatus postingStatus;
        final int okStatus;

        Op(HttpMethod method, String path, String body, Target target, JobPostingStatus postingStatus, int okStatus) {
            this.method = method; this.path = path; this.body = body; this.target = target;
            this.postingStatus = postingStatus; this.okStatus = okStatus;
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
    private final List<Actor> outsiders = new ArrayList<>();
    private Long team, requester, applicant, teamAdmin;

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
            applicant = user();
            member(applicant, team);
            teamAdmin = user();
            member(teamAdmin, team);
            assignRole(teamAdmin, "ADMIN", team);
            outsiders.add(new Actor("所属なし", user()));
            Long plainMember = user();
            member(plainMember, team);
            outsiders.add(new Actor("同TEAMのMEMBER", plainMember));
            Long foreignAdmin = user();
            member(foreignAdmin, foreignTeam);
            assignRole(foreignAdmin, "ADMIN", foreignTeam);
            outsiders.add(new Actor("他TEAMのADMIN", foreignAdmin));
            assertThat(postings.existsById(MISSING)).isFalse();
            assertThat(applications.existsById(MISSING)).isFalse();
            assertThat(contracts.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有行を削除して共有mockと認証状態を復元する() {
        try {
            tx(() -> {
                if (!postingIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM job_qr_tokens WHERE job_contract_id IN "
                            + "(SELECT id FROM job_contracts WHERE job_posting_id IN (:ids))").setParameter("ids", postingIds).executeUpdate();
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
    @DisplayName("AC-1027-T2-1: 権限外の実在ID操作と不在IDの応答が status・error.code・message まで一致する")
    void AC_1027_T2_1_越境と不在の本文一致() throws Exception {
        List<String> diffs = new ArrayList<>();
        for (Op op : Op.values()) {
            Fixture f = fixture(op);
            for (Actor actor : outsiders) {
                String token = tokens.issueAccessToken(actor.id(), List.of("USER"));
                MvcResult missing = call(op, token, MISSING);
                MvcResult existing = call(op, token, f.target(op));
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
    @DisplayName("AC-1027-T2-2: 権限外操作の後も求人・応募・契約・QRトークンの行は不変")
    void AC_1027_T2_2_越境操作はDB不変() throws Exception {
        for (Op op : Op.values()) {
            Fixture f = fixture(op);
            Snapshot before = snapshot();
            for (Actor actor : outsiders) {
                call(op, tokens.issueAccessToken(actor.id(), List.of("USER")), f.target(op));
            }
            assertThat(snapshot()).as("%s", op).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("AC-1027-T2-3: 投稿者・TEAM ADMIN・応募者本人・Requester の正常系を維持する")
    void AC_1027_T2_3_権限者の正常系() throws Exception {
        for (Op op : Op.values()) {
            Fixture f = fixture(op);
            Long actor = switch (op) {
                case POSTING_UPDATE, POSTING_PUBLISH, POSTING_CANCEL, POSTING_DELETE, QR_ISSUE, QR_CURRENT -> requester;
                case POSTING_CLOSE, APPLICATION_LIST, APPLICATION_REJECT, APPLICATION_ACCEPT -> teamAdmin;
                case APPLICATION_WITHDRAW -> applicant;
            };
            MvcResult result = call(op, tokens.issueAccessToken(actor, List.of("USER")), f.target(op));
            assertThat(result.getResponse().getStatus()).as("%s: %s", op,
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(op.okStatus);
        }
    }

    @Test
    @DisplayName("AC-1027-T2-4: 未認証は実在・不在とも401でDB不変")
    void AC_1027_T2_4_未認証は401() throws Exception {
        for (Op op : Op.values()) {
            Fixture f = fixture(op);
            Snapshot before = snapshot();
            for (long target : List.of(f.target(op), MISSING)) {
                assertThat(call(op, null, target).getResponse().getStatus()).as("%s", op).isEqualTo(401);
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    private MvcResult call(Op op, String token, long id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var builder = request(op.method, op.path.replace("{id}", Long.toString(id))).header("Accept-Language", "ja");
        if (op.body != null) builder.contentType(MediaType.APPLICATION_JSON).content(op.body);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try { return mvc.perform(builder).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private JsonNode json(MvcResult response) throws Exception {
        String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }

    /** 操作ごとに独立した求人・応募（APPLIED）・契約（MATCHED）を作る。 */
    private Fixture fixture(Op op) {
        return tx(() -> {
            LocalDateTime start = LocalDateTime.now().plusDays(10).withNano(0);
            JobPostingEntity posting = postings.save(JobPostingEntity.builder().teamId(team).createdByUserId(requester)
                    .title("求人書込試練").description("越境書込の求人").workLocationType(WorkLocationType.ONSITE)
                    .workAddress("試練会場").workStartAt(start).workEndAt(start.plusHours(4))
                    .rewardType(RewardType.LUMP_SUM).baseRewardJpy(3000).capacity(2).applicationDeadlineAt(start.minusDays(1))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS).status(op.postingStatus).build());
            postingIds.add(posting.getId());
            Long applicationId = null;
            Long contractId = null;
            if (op.target != Target.POSTING) {
                JobApplicationEntity application = applications.save(JobApplicationEntity.builder().jobPostingId(posting.getId())
                        .applicantUserId(applicant).selfPr("自己PR")
                        .status(op.target == Target.CONTRACT ? JobApplicationStatus.ACCEPTED : JobApplicationStatus.APPLIED)
                        .appliedAt(LocalDateTime.now().minusHours(1).withNano(0)).build());
                applicationId = application.getId();
                if (op.target == Target.CONTRACT) {
                    contractId = contracts.save(JobContractEntity.builder().jobPostingId(posting.getId())
                            .jobApplicationId(applicationId).requesterUserId(requester).workerUserId(applicant)
                            .baseRewardJpy(3000).workStartAt(start).workEndAt(start.plusHours(4))
                            .status(JobContractStatus.MATCHED).matchedAt(LocalDateTime.now().withNano(0)).build()).getId();
                }
            }
            em.flush();
            return new Fixture(posting.getId(), applicationId, contractId);
        });
    }

    private void member(Long user, Long scope) {
        memberships.save(MembershipEntity.builder().userId(user).scopeType(ScopeType.TEAM)
                .scopeId(scope).roleKind(RoleKind.MEMBER).build());
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("posting-write-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("求人").displayName("求人書込").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }

    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("pw-" + UUID.randomUUID().toString().substring(0, 8))
                .name("求人書込").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
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
            rows.add(Arrays.deepToString(em.createNativeQuery("SELECT * FROM job_qr_tokens WHERE job_contract_id IN "
                    + "(SELECT id FROM job_contracts WHERE job_posting_id IN (:ids)) ORDER BY id")
                    .setParameter("ids", postingIds).getResultList().toArray()));
            return new Snapshot(List.copyOf(rows));
        });
    }

    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private record Actor(String name, Long id) { }
    private record Snapshot(List<String> rows) { }
    private record Fixture(Long posting, Long application, Long contract) {
        long target(Op op) {
            return switch (op.target) {
                case POSTING -> posting;
                case APPLICATION -> application;
                case CONTRACT -> contract;
            };
        }
    }
}
