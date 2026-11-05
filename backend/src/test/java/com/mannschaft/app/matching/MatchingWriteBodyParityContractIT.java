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
import com.mannschaft.app.matching.entity.NgTeamEntity;
import com.mannschaft.app.matching.repository.NgTeamRepository;
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
 * <p>契約は2系統。対象を閲覧できない者（非閲覧者）が実在 ID に操作した応答は、不在 ID の応答と
 * status・error.code・message まで完全一致する（閲覧ガード {@code MatchRequestViewAccessGuard} /
 * {@code MatchProposalViewAccessGuard} が書込TX・行ロックより前に不在コードで拒否）。
 * 対象を閲覧できる者（閲覧者）の権限不足は従来の診断 {@code MATCHING_010}、親募集が削除済みの応募を閲覧できる
 * 応募TEAMの者には従来の親不在診断 {@code MATCHING_001} を維持する。金型は {@link MatchProposalCancelScopeContractIT}。</p>
 *
 * <p>閲覧資格は詳細取得と同じ: 募集は「所属TEAM群のいずれともNG関係が無く、OPEN か自所属TEAMの募集」、
 * 応募は「応募TEAM・（親が残っていれば）募集TEAMの所属者・管理者」。</p>
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
        // OPEN 以外の募集は自所属TEAM以外から見えない。非所属者の更新・削除・応募一覧は不在と同一応答。
        REQUEST_UPDATE_NOT_OPEN(HttpMethod.PUT, "/api/v1/matching/requests/{id}", Target.REQUEST, "MATCHING_001", Variant.NOT_OPEN),
        REQUEST_DELETE_NOT_OPEN(HttpMethod.DELETE, "/api/v1/matching/requests/{id}", Target.REQUEST, "MATCHING_001", Variant.NOT_OPEN),
        PROPOSAL_LIST_NOT_OPEN(HttpMethod.GET, "/api/v1/matching/requests/{id}/proposals", Target.REQUEST, "MATCHING_001", Variant.NOT_OPEN),
        PROPOSAL_ACCEPT(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/accept", Target.PROPOSAL, "MATCHING_002"),
        PROPOSAL_REJECT(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/reject", Target.PROPOSAL, "MATCHING_002"),
        PROPOSAL_WITHDRAW(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/withdraw", Target.PROPOSAL, "MATCHING_002"),
        // 親の募集が論理削除された後の承諾・拒否は、不在の応募と同一応答（MATCHING_002）。
        PROPOSAL_ACCEPT_PARENT_DELETED(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/accept", Target.PROPOSAL, "MATCHING_002", Variant.PARENT_DELETED),
        PROPOSAL_REJECT_PARENT_DELETED(HttpMethod.PATCH, "/api/v1/matching/proposals/{id}/reject", Target.PROPOSAL, "MATCHING_002", Variant.PARENT_DELETED),
        // 応募作成。他チームから見えない募集（OPEN以外・NG関係）は不在の募集と同一応答（MATCHING_001）。
        PROPOSAL_CREATE_NOT_OPEN(HttpMethod.POST, "/api/v1/teams/{team}/matching/requests/{id}/propose", Target.REQUEST, "MATCHING_001", Variant.NOT_OPEN),
        PROPOSAL_CREATE_NG(HttpMethod.POST, "/api/v1/teams/{team}/matching/requests/{id}/propose", Target.REQUEST, "MATCHING_001", Variant.NG),
        // パスのTEAMではなく、利用者の別の所属TEAMが募集TEAMとNG関係にある場合も、詳細取得と同じく不在と同一応答。
        PROPOSAL_CREATE_NG_OTHER_TEAM(HttpMethod.POST, "/api/v1/teams/{team}/matching/requests/{id}/propose", Target.REQUEST, "MATCHING_001", Variant.NG_OTHER_TEAM);

        final HttpMethod method;
        final String path;
        final Target target;
        final String missingCode;
        final Variant variant;

        Op(HttpMethod method, String path, Target target, String missingCode) {
            this(method, path, target, missingCode, Variant.NORMAL);
        }

        Op(HttpMethod method, String path, Target target, String missingCode, Variant variant) {
            this.method = method; this.path = path; this.target = target; this.missingCode = missingCode;
            this.variant = variant;
        }

        boolean isCreate() { return path.endsWith("/propose"); }

        boolean isProposalDecision() {
            return variant == Variant.NORMAL && (this == PROPOSAL_ACCEPT || this == PROPOSAL_REJECT || this == PROPOSAL_WITHDRAW);
        }
    }

    private enum Variant { NORMAL, PARENT_DELETED, NOT_OPEN, NG, NG_OTHER_TEAM }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRequestRepository requests;
    @Autowired private MatchProposalRepository proposals;
    @Autowired private NgTeamRepository ngTeams;
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
    private Long requestingTeam, proposingTeam, requestingAdmin, proposingAdmin, sideTeam, multiTeamAdmin, requestingMember;
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
            requestingMember = member(requestingTeam);
            outsiders.add(new Actor("募集TEAMのMEMBER", requestingMember));
            // 応募TEAMのADMINでありつつ、別TEAM（sideTeam）にも所属する者。
            sideTeam = team();
            multiTeamAdmin = admin(proposingTeam, "ADMIN", true);
            memberships.save(MembershipEntity.builder().userId(multiTeamAdmin).scopeType(ScopeType.TEAM)
                    .scopeId(sideTeam).roleKind(RoleKind.MEMBER).build());
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
                if (!teamIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM ng_teams WHERE team_id IN (:ids) OR blocked_team_id IN (:ids)")
                            .setParameter("ids", teamIds).executeUpdate();
                }
                if (!proposalIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM match_reviews WHERE proposal_id IN (:ids)")
                            .setParameter("ids", proposalIds).executeUpdate();
                }
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
    @DisplayName("非閲覧者は不在IDと status・error.code・message まで一致し、閲覧者は従来の診断を受け、DBは不変")
    void 越境と不在の本文一致とDB不変() throws Exception {
        List<String> diffs = new ArrayList<>();
        for (Op op : Op.values()) {
            Fixture f = fixture(op);
            for (Actor actor : actorsFor(op)) {
                Expect expect = expected(op, actor);
                if (expect == Expect.ALLOWED) { continue; }
                Snapshot before = snapshot();
                MvcResult missing = call(op, actor.id(), MISSING);
                MvcResult existing = call(op, actor.id(), f.target(op));
                assertThat(missing.getResponse().getStatus()).as("%s 不在", op).isEqualTo(404);
                assertThat(error(missing).path("code").asText()).as("%s 不在のコード", op).isEqualTo(op.missingCode);
                if (expect == Expect.PERMISSION_DENIED) {
                    if (!"MATCHING_010".equals(error(existing).path("code").asText())) {
                        diffs.add(op + "/" + actor.name() + ": 閲覧者の診断が MATCHING_010 でない: "
                                + existing.getResponse().getStatus() + " " + error(existing));
                    }
                } else if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                        || !error(existing).equals(error(missing))) {
                    diffs.add(op + "/" + actor.name() + ": 越境=" + existing.getResponse().getStatus() + " " + error(existing)
                            + " 不在=" + missing.getResponse().getStatus() + " " + error(missing));
                }
                assertThat(snapshot()).as("%s/%s 拒否によるDB変更なし", op, actor.name()).isEqualTo(before);
            }
        }
        assertThat(diffs).as("非閲覧者で越境と不在が割れている、または閲覧者の診断が失われた操作").isEmpty();
    }

    @Test
    @DisplayName("親募集が削除済みの応募を閲覧できる応募TEAMの者には、従来の親不在診断 MATCHING_001 を返し、DBは不変")
    void 削除済み親の応募の閲覧者は親不在診断() throws Exception {
        for (Op op : List.of(Op.PROPOSAL_ACCEPT_PARENT_DELETED, Op.PROPOSAL_REJECT_PARENT_DELETED)) {
            Fixture f = fixture(op);
            Snapshot before = snapshot();
            MvcResult result = call(op, proposingAdmin, f.target(op));
            assertThat(result.getResponse().getStatus()).as("%s", op).isEqualTo(404);
            assertThat(error(result).path("code").asText()).as("%s", op).isEqualTo("MATCHING_001");
            assertThat(snapshot()).as("%s", op).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("レビュー投稿: 非閲覧者は親募集の有無を問わず不在応募と完全一致し、閲覧者（募集TEAMのMEMBER）は従来の MATCHING_014、DBは不変")
    void レビュー投稿の越境と不在の本文一致() throws Exception {
        List<String> diffs = new ArrayList<>();
        for (boolean parentDeleted : List.of(false, true)) {
            long proposalId = acceptedProposal(parentDeleted);
            for (Actor actor : outsiders) {
                boolean viewer = actor.id().equals(requestingMember) && !parentDeleted;
                Snapshot before = snapshot();
                long reviewsBefore = reviewCount();
                MvcResult missing = review(actor.id(), MISSING);
                MvcResult existing = review(actor.id(), proposalId);
                assertThat(missing.getResponse().getStatus()).isEqualTo(404);
                assertThat(error(missing).path("code").asText()).isEqualTo("MATCHING_002");
                if (viewer) {
                    if (!"MATCHING_014".equals(error(existing).path("code").asText())) {
                        diffs.add("親削除=" + parentDeleted + "/" + actor.name() + ": 閲覧者の診断が MATCHING_014 でない: "
                                + existing.getResponse().getStatus() + " " + error(existing));
                    }
                } else if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                        || !error(existing).equals(error(missing))) {
                    diffs.add("親削除=" + parentDeleted + "/" + actor.name() + ": 越境=" + existing.getResponse().getStatus()
                            + " " + error(existing) + " 不在=" + error(missing));
                }
                assertThat(snapshot()).isEqualTo(before);
                assertThat(reviewCount()).isEqualTo(reviewsBefore);
            }
        }
        assertThat(diffs).as("レビュー投稿で越境と不在が割れている、または閲覧者の診断が失われた").isEmpty();
    }

    @Test
    @DisplayName("レビュー投稿: 親募集が削除済みの応募を応募TEAMのADMINが投稿すると従来の親不在診断 MATCHING_001、正常系は201")
    void レビュー投稿の閲覧者の診断と正常系() throws Exception {
        MvcResult deletedParent = review(proposingAdmin, acceptedProposal(true));
        assertThat(error(deletedParent).path("code").asText()).isEqualTo("MATCHING_001");
        MvcResult ok = review(proposingAdmin, acceptedProposal(false));
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
    }

    private long acceptedProposal(boolean parentDeleted) {
        return tx(() -> {
            MatchRequestEntity parent = requests.save(MatchRequestEntity.builder().teamId(requestingTeam)
                    .title("レビュー契約").activityType(ActivityType.values()[0]).prefectureCode("13")
                    .status(MatchRequestStatus.MATCHED).build());
            requestIds.add(parent.getId());
            MatchProposalEntity proposal = proposals.save(MatchProposalEntity.builder().requestId(parent.getId())
                    .proposingTeamId(proposingTeam).status(MatchProposalStatus.ACCEPTED).build());
            proposalIds.add(proposal.getId());
            em.flush();
            if (parentDeleted) {
                parent.softDelete();
                requests.save(parent);
            }
            return proposal.getId();
        });
    }

    private MvcResult review(Long actor, long proposalId) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var builder = request(HttpMethod.POST, "/api/v1/matching/reviews").header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proposalId\":" + proposalId + ",\"rating\":5,\"comment\":\"良い試合\",\"isPublic\":true}");
        if (actor != null) { builder.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))); }
        try { return mvc.perform(builder).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private long reviewCount() {
        return tx(() -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM match_reviews WHERE proposal_id IN (:ids)")
                .setParameter("ids", proposalIds.isEmpty() ? List.of(-1L) : proposalIds).getSingleResult()).longValue());
    }

    /** 閲覧資格に応じた期待。 */
    private enum Expect { HIDDEN, PERMISSION_DENIED, ALLOWED }

    /**
     * 操作者ごとの期待（詳細取得・一覧と同じ閲覧資格）。
     * OPEN 募集は NG 関係の無い誰からも見えるため、その更新・削除・応募一覧の権限不足は従来コード。
     * OPEN 以外の募集は募集TEAMの所属者にだけ見える。応募は募集TEAM・応募TEAMの所属者にだけ見え、
     * 親募集が削除済みなら募集TEAMからは見えない。
     */
    private Expect expected(Op op, Actor actor) {
        if (op.isCreate()) { return Expect.HIDDEN; }
        boolean requestingTeamMember = actor.id().equals(requestingMember);
        if (op == Op.PROPOSAL_LIST || op == Op.PROPOSAL_LIST_NOT_OPEN) {
            if (requestingTeamMember) { return Expect.ALLOWED; }
            return op.variant == Variant.NORMAL ? Expect.PERMISSION_DENIED : Expect.HIDDEN;
        }
        if (op.target == Target.REQUEST) {
            return requestingTeamMember || op.variant == Variant.NORMAL ? Expect.PERMISSION_DENIED : Expect.HIDDEN;
        }
        return requestingTeamMember && op.variant == Variant.NORMAL ? Expect.PERMISSION_DENIED : Expect.HIDDEN;
    }

    @Test
    @DisplayName("応募を閲覧できる者の権限不足は従来コード MATCHING_010 を維持し、DBは不変")
    void 閲覧できる者の権限不足は従来コード() throws Exception {
        Object[][] cases = {
                {Op.PROPOSAL_ACCEPT, "募集TEAMのMEMBER"}, {Op.PROPOSAL_REJECT, "募集TEAMのMEMBER"},
                {Op.PROPOSAL_WITHDRAW, "募集TEAMのMEMBER"}, {Op.PROPOSAL_WITHDRAW, "募集TEAMのADMIN"}};
        for (Object[] c : cases) {
            Op op = (Op) c[0];
            Long actor = c[1].equals("募集TEAMのADMIN") ? requestingAdmin : requestingMember;
            Fixture f = fixture(op);
            Snapshot before = snapshot();
            MvcResult result = call(op, actor, f.target(op));
            assertThat(error(result).path("code").asText()).as("%s/%s", op, c[1]).isEqualTo("MATCHING_010");
            assertThat(snapshot()).as("%s/%s", op, c[1]).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("募集TEAM ADMIN・応募TEAM ADMIN の正常系を維持する")
    void 権限者の正常系() throws Exception {
        for (Op op : Op.values()) {
            if (op.variant != Variant.NORMAL) { continue; }
            Fixture f = fixture(op);
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
            Fixture f = fixture(op);
            Snapshot before = snapshot();
            for (long target : List.of(f.target(op), MISSING)) {
                assertThat(call(op, null, target).getResponse().getStatus()).as("%s", op).isEqualTo(401);
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    /** 応募作成は応募TEAMのADMIN（パスTEAMの認可を通る者）が操作者。それ以外は越境者。 */
    private List<Actor> actorsFor(Op op) {
        if (op.variant == Variant.NG_OTHER_TEAM) { return List.of(new Actor("応募TEAMのADMIN兼別TEAM所属", multiTeamAdmin)); }
        return op.isCreate() ? List.of(new Actor("応募TEAMのADMIN", proposingAdmin)) : outsiders;
    }

    @Test
    @DisplayName("応募作成の正常系（OPEN募集・応募TEAMのADMIN）は201を維持する")
    void 応募作成の正常系() throws Exception {
        MatchRequestEntity parent = tx(() -> {
            MatchRequestEntity saved = requests.save(MatchRequestEntity.builder().teamId(requestingTeam)
                    .title("応募作成").activityType(ActivityType.values()[0]).prefectureCode("13")
                    .status(MatchRequestStatus.OPEN).build());
            requestIds.add(saved.getId());
            return saved;
        });
        MvcResult result = mvc.perform(request(HttpMethod.POST,
                        "/api/v1/teams/" + proposingTeam + "/matching/requests/" + parent.getId() + "/propose")
                .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer " + tokens.issueAccessToken(proposingAdmin, List.of("USER")))).andReturn();
        SecurityContextHolder.clearContext();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        tx(() -> {
            proposalIds.addAll(proposals.findAll().stream()
                    .filter(p -> p.getRequestId().equals(parent.getId())).map(MatchProposalEntity::getId).toList());
            return null;
        });
    }

    private MvcResult call(Op op, Long actor, long id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var builder = request(op.method, op.path.replace("{team}", Long.toString(proposingTeam)).replace("{id}", Long.toString(id))).header("Accept-Language", "ja");
        if (op.method == HttpMethod.PUT) {
            builder.contentType(MediaType.APPLICATION_JSON).content(
                    "{\"title\":\"改題\",\"activityType\":\"" + ActivityType.values()[0].name() + "\",\"prefectureCode\":\"13\"}");
        } else if (op.method == HttpMethod.PATCH || op.method == HttpMethod.POST) {
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
    private Fixture fixture(Op op) {
        return tx(() -> {
            MatchRequestEntity parent = requests.save(MatchRequestEntity.builder().teamId(requestingTeam)
                    .title("書込契約").activityType(ActivityType.values()[0]).prefectureCode("13")
                    .status(op.variant == Variant.NOT_OPEN ? MatchRequestStatus.MATCHED : MatchRequestStatus.OPEN).build());
            requestIds.add(parent.getId());
            MatchProposalEntity proposal = proposals.save(MatchProposalEntity.builder().requestId(parent.getId())
                    .proposingTeamId(proposingTeam).status(MatchProposalStatus.PENDING).build());
            proposalIds.add(proposal.getId());
            em.flush();
            if (op.variant == Variant.PARENT_DELETED) {
                parent.softDelete();
                requests.save(parent);
            }
            if (op.variant == Variant.NG) {
                ngTeams.save(NgTeamEntity.builder().teamId(requestingTeam).blockedTeamId(proposingTeam).build());
            }
            if (op.variant == Variant.NG_OTHER_TEAM) {
                ngTeams.save(NgTeamEntity.builder().teamId(sideTeam).blockedTeamId(requestingTeam).build());
            }
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
