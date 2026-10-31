package com.mannschaft.app.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.service.MatchProposalCancellationWriter;
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
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-260821-1027 追加分: 募集・応募の書込（および非公開の応募一覧）における存在オラクルの本文正規化。
 *
 * <p>権限外の者が実在 ID に操作した応答と不在 ID の応答が、status・error.code・message まで
 * 完全一致することを契約とする。修正前は権限外が {@code MATCHING_010}、不在が
 * {@code MATCHING_001} / {@code MATCHING_002} で本文が割れる。金型は {@link MatchProposalCancelScopeContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260821-1027 募集/応募書込の越境/不在 本文一致契約")
class MatchingWriteBodyParityContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    private static final String FEATURE = "FEATURE_MATCHING_ENABLED";

    private enum Target { REQUEST, PROPOSAL }

    private enum Op {
        REQUEST_UPDATE(HttpMethod.PUT, "/api/v1/matching/requests/{id}", Target.REQUEST, "MATCHING_001"),
        REQUEST_DELETE(HttpMethod.DELETE, "/api/v1/matching/requests/{id}", Target.REQUEST, "MATCHING_001"),
        PROPOSAL_LIST(HttpMethod.GET, "/api/v1/matching/requests/{id}/proposals", Target.REQUEST, "MATCHING_001"),
        PROPOSAL_ACCEPT(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/accept", Target.PROPOSAL, "MATCHING_002"),
        PROPOSAL_REJECT(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/reject", Target.PROPOSAL, "MATCHING_002"),
        PROPOSAL_WITHDRAW(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/withdraw", Target.PROPOSAL, "MATCHING_002");

        final HttpMethod method;
        final String path;
        final Target target;
        final String missingCode;

        Op(HttpMethod method, String path, Target target, String missingCode) {
            this.method = method; this.path = path; this.target = target; this.missingCode = missingCode;
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRequestRepository requests;
    @Autowired private MatchProposalRepository proposals;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager caches;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @PersistenceContext private EntityManager em;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> requestIds = new ArrayList<>();
    private final List<Long> proposalIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Actor> outsiders = new ArrayList<>();
    private Long requestingTeam, proposingTeam, requestingAdmin, proposingAdmin;
    private Long flagId;
    private FlagState previousFlag;
    private boolean createdFlag;

    @BeforeEach
    void fixtureを独立TXで確定する() {
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
            requestingTeam = team();
            proposingTeam = team();
            Long foreignTeam = team();
            requestingAdmin = admin(requestingTeam, "ADMIN", true);
            proposingAdmin = admin(proposingTeam, "ADMIN", true);
            outsiders.add(new Actor("所属なし", user()));
            outsiders.add(new Actor("他TEAMのADMIN", admin(foreignTeam, "ADMIN", true)));
            outsiders.add(new Actor("募集TEAMのMEMBER", member(requestingTeam)));
            assertThat(requests.existsById(MISSING)).isFalse();
            assertThat(proposals.existsById(MISSING)).isFalse();
            return null;
        });
        enableFlag();
    }

    @AfterEach
    void 自所有fixtureと共有flagを復元する() {
        try {
            tx(() -> {
                proposals.deleteAllById(proposalIds);
                if (!requestIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM match_requests WHERE id IN (:ids)")
                            .setParameter("ids", requestIds).executeUpdate();
                }
                em.flush();
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                roles.deleteAllById(createdRoleIds);
                teams.deleteAllById(teamIds);
                return null;
            });
        } finally {
            try { restoreFlag(); }
            finally {
                try { evictFlag(); }
                finally {
                    try { reset(redisTemplate); } finally { SecurityContextHolder.clearContext(); }
                }
            }
        }
    }

    @Test
    @DisplayName("権限外の実在ID操作と不在IDの応答が status・error.code・message まで一致し、DBは不変")
    void 越境と不在の本文一致とDB不変() throws Exception {
        List<String> diffs = new ArrayList<>();
        for (Op op : Op.values()) {
            Fixture f = fixture();
            for (Actor actor : outsiders) {
                // 募集TEAMのMEMBERは応募一覧を閲覧できる（所属者の正当な読み取り）ため対象外。
                if (op == Op.PROPOSAL_LIST && actor.name().equals("募集TEAMのMEMBER")) { continue; }
                Snapshot before = snapshot();
                MvcResult missing = call(op, actor.id(), MISSING);
                MvcResult existing = call(op, actor.id(), f.target(op));
                assertThat(missing.getResponse().getStatus()).as("%s 不在", op).isEqualTo(404);
                assertThat(error(missing).path("code").asText()).as("%s 不在のコード", op).isEqualTo(op.missingCode);
                if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                        || !error(existing).equals(error(missing))) {
                    diffs.add(op + "/" + actor.name() + ": 越境=" + existing.getResponse().getStatus() + " " + error(existing)
                            + " 不在=" + missing.getResponse().getStatus() + " " + error(missing));
                }
                assertThat(snapshot()).as("%s/%s 拒否によるDB変更なし", op, actor.name()).isEqualTo(before);
            }
        }
        assertThat(diffs).as("越境と不在で応答が割れている操作").isEmpty();
    }

    @Test
    @DisplayName("募集TEAM ADMIN・応募TEAM ADMIN の正常系を維持する")
    void 権限者の正常系() throws Exception {
        for (Op op : Op.values()) {
            Fixture f = fixture();
            Long actor = op == Op.PROPOSAL_WITHDRAW ? proposingAdmin : requestingAdmin;
            MvcResult result = call(op, actor, f.target(op));
            assertThat(result.getResponse().getStatus()).as("%s: %s", op,
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(op == Op.REQUEST_DELETE ? 204 : 200);
        }
    }

    @Test
    @DisplayName("未認証は実在・不在とも401でDB不変")
    void 未認証は401() throws Exception {
        for (Op op : Op.values()) {
            Fixture f = fixture();
            Snapshot before = snapshot();
            for (long target : List.of(f.target(op), MISSING)) {
                assertThat(call(op, null, target).getResponse().getStatus()).as("%s", op).isEqualTo(401);
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    private MvcResult call(Op op, Long actor, long id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var builder = request(op.method, op.path.replace("{id}", Long.toString(id))).header("Accept-Language", "ja");
        if (op == Op.REQUEST_UPDATE) {
            builder.contentType(MediaType.APPLICATION_JSON).content(
                    "{\"title\":\"改題\",\"activityType\":\"" + ActivityType.values()[0].name() + "\",\"prefectureCode\":\"13\"}");
        } else if (op.method == HttpMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        if (actor != null) { builder.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))); }
        try { return mvc.perform(builder).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private JsonNode error(MvcResult response) throws Exception {
        String text = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? mapper.nullNode() : mapper.readTree(text).path("error");
    }

    /** 操作ごとに独立した OPEN 募集と PENDING 応募を作る。 */
    private Fixture fixture() {
        return tx(() -> {
            MatchRequestEntity parent = requests.save(MatchRequestEntity.builder().teamId(requestingTeam)
                    .title("書込契約").activityType(ActivityType.values()[0]).prefectureCode("13")
                    .status(MatchRequestStatus.OPEN).build());
            requestIds.add(parent.getId());
            MatchProposalEntity proposal = proposals.save(MatchProposalEntity.builder().requestId(parent.getId())
                    .proposingTeamId(proposingTeam).status(MatchProposalStatus.PENDING).build());
            proposalIds.add(proposal.getId());
            em.flush();
            return new Fixture(parent.getId(), proposal.getId());
        });
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("matching-write-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("応募").displayName("応募契約").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }
    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("pc-" + UUID.randomUUID().toString().substring(0, 8))
                .name("応募契約").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
        teamIds.add(id); return id;
    }
    private Long member(Long team) {
        Long id = user();
        role("MEMBER");
        memberships.save(MembershipEntity.builder().userId(id).scopeType(ScopeType.TEAM).scopeId(team)
                .roleKind(RoleKind.MEMBER).build());
        return id;
    }
    private Long admin(Long team, String role, boolean membership) {
        Long id = membership ? member(team) : user(); assignRole(id, role, team); return id;
    }
    private RoleEntity role(String name) {
        // 共有の固定ロールを既存の正準priorityでseedする。手書きINSERTは使わない。
        return roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder().name(name)
                    .displayName(name).priority(RolePriority.priority(name)).isSystem(false).build());
            createdRoleIds.add(created.getId());
            return created;
        });
    }
    private void assignRole(Long user, String name, Long team) {
        userRoles.save(UserRoleEntity.builder().userId(user).roleId(role(name).getId()).teamId(team).build());
    }
    private Snapshot snapshot() {
        return tx(() -> {
            em.flush(); em.clear();
            List<?> child = em.createNativeQuery("SELECT * FROM match_proposals WHERE proposing_team_id=:team ORDER BY id").setParameter("team", proposingTeam).getResultList();
            List<?> parent = em.createNativeQuery("SELECT * FROM match_requests WHERE team_id=:team ORDER BY id").setParameter("team", requestingTeam).getResultList();
            return new Snapshot(Arrays.deepToString(child.toArray()), Arrays.deepToString(parent.toArray()));
        });
    }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private void enableFlag() {
        tx(() -> {
            FeatureFlagEntity flag = flags.findByFlagKey(FEATURE).orElse(null);
            if (flag == null) {
                flag = flags.save(FeatureFlagEntity.builder().flagKey(FEATURE).isEnabled(true).description("CMP1027応募契約fixture").build()); createdFlag = true;
            } else {
                previousFlag = new FlagState(flag.getId(), flag.getIsEnabled(), flag.getUpdatedBy(), flag.getUpdatedAt()); flag.updateFlag(true, flag.getUpdatedBy());
            }
            flagId = flag.getId(); em.flush(); return null;
        }); evictFlag();
    }
    private void restoreFlag() {
        if (flagId == null) return;
        tx(() -> {
            if (createdFlag) flags.deleteById(flagId);
            else assertThat(em.createNativeQuery("UPDATE feature_flags SET is_enabled=:enabled, updated_by=:actor, updated_at=:updated WHERE id=:id AND flag_key=:key")
                    .setParameter("enabled", previousFlag.enabled()).setParameter("actor", previousFlag.updatedBy()).setParameter("updated", previousFlag.updatedAt())
                    .setParameter("id", previousFlag.id()).setParameter("key", FEATURE).executeUpdate()).isEqualTo(1);
            return null;
        });
    }
    private void evictFlag() { if (flagId != null) { Cache cache = caches.getCache("featureFlags"); if (cache != null) cache.evict(FEATURE); } }
    private record Actor(String name, Long id) { }
    private record Snapshot(String proposals, String requests) { }
    private record FlagState(Long id, Boolean enabled, Long updatedBy, LocalDateTime updatedAt) { }
    private record Fixture(Long request, Long proposal) {
        long target(Op op) { return op.target == Target.REQUEST ? request : proposal; }
    }
}
