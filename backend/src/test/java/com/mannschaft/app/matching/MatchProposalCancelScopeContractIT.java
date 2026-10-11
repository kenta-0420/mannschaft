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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * CMP-260821-1027: cancel/agree-cancel の私的子IDと状態診断の契約。
 * 実JWT・Security・認可・MySQL・GHEを通す。状態を読む権限と変更権限を分け、
 * 非readerの全状態を不在と同じ404にする。募集親を復元する設計は対象外。
 * fixtureは独立TXで確定し、失敗TXのロールバックだけでDB不変を代用しない。
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP1027 Proposal cancel/agree-cancel の存在秘匿と既存診断")
class MatchProposalCancelScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    private static final String FEATURE = "FEATURE_MATCHING_ENABLED";
    @Autowired private MockMvc mvc;
    @Autowired private AccessControlService accessControl;
    @Autowired private MatchProposalCancellationWriter cancellationWriter;
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
    private final List<Long> requestingAdmins = new ArrayList<>();
    private final List<Long> proposingAdmins = new ArrayList<>();
    private Long requestingTeam, proposingTeam, outsider, foreignAdmin, requestingMember, proposingMember;
    private Long system, systemAdmin, dualAdmin;
    private Long flagId;
    private FlagState previousFlag;
    private boolean createdFlag;
    private boolean unfinishedWorkers;

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
            outsider = user();
            foreignAdmin = admin(foreignTeam, "ADMIN", true);
            requestingMember = member(requestingTeam);
            proposingMember = member(proposingTeam);
            for (String role : List.of("ADMIN", "DEPUTY_ADMIN")) {
                for (boolean membership : List.of(true, false)) {
                    requestingAdmins.add(admin(requestingTeam, role, membership));
                    proposingAdmins.add(admin(proposingTeam, role, membership));
                }
            }
            system = user();
            assignRole(system, "SYSTEM_ADMIN", null);
            systemAdmin = admin(proposingTeam, "ADMIN", false);
            assignRole(systemAdmin, "SYSTEM_ADMIN", null);
            dualAdmin = admin(requestingTeam, "ADMIN", false);
            assignRole(dualAdmin, "ADMIN", proposingTeam);
            assertThat(proposals.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有fixtureと共有flagを復元する() {
        if (unfinishedWorkers) {
            SecurityContextHolder.clearContext();
            throw new IllegalStateException("worker終了未確認: advisor・自所有fixtureを保全して調査する");
        }
        try {
            tx(() -> {
                proposals.deleteAllById(proposalIds);
                // SQLRestrictionで見えない自所有の論理削除親も片付ける。
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
                teams.deleteAllById(teamIds);
                return null;
            });
        } finally {
            try {
                restoreFlag();
            } finally {
                try {
                    evictFlag();
                } finally {
                    try {
                        reset(redisTemplate);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }
            }
        }
    }

    @Test
    void P1_P3非readerは両EP全状態全canceltypeで不在と同じ404本文かつDB不変() throws Exception {
        for (MatchProposalStatus state : MatchProposalStatus.values()) {
            for (CancellationType type : cancellationTypes()) {
                Long id = fixture(state, type);
                for (Long actor : List.of(outsider, foreignAdmin)) {
                    for (String endpoint : List.of("cancel", "agree-cancel")) {
                        Snapshot before = snapshot();
                        MvcResult missing = call(actor, endpoint, MISSING, "{}");
                        MvcResult existing = call(actor, endpoint, id, "{}");
                        sameNotFound(missing, existing);
                        assertThat(snapshot()).as("拒否による全列/件数変更なし").isEqualTo(before);
                    }
                }
            }
        }
    }

    @Test
    void P4_P5両側MEMBERの実在閲覧と状態診断と変更拒否を維持する() throws Exception {
        enableFlag();
        Long accepted = fixture(MatchProposalStatus.ACCEPTED, null);
        Long pending = fixture(MatchProposalStatus.PENDING, null);
        Long unilateral = fixture(MatchProposalStatus.CANCELLED, CancellationType.UNILATERAL);
        Long mutual = fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING);
        Long requestId = tx(() -> proposals.findById(accepted).orElseThrow().getRequestId());
        MvcResult requesterRead = http(requestingMember, get("/api/v1/matching/requests/" + requestId + "/proposals"));
        MvcResult proposerRead = http(proposingMember, get("/api/v1/teams/" + proposingTeam + "/matching/proposals"));
        for (MvcResult read : List.of(requesterRead, proposerRead)) {
            assertThat(read.getResponse().getStatus()).isEqualTo(200);
            assertThat(mapper.readTree(read.getResponse().getContentAsString(StandardCharsets.UTF_8)).findValues("id"))
                    .extracting(JsonNode::asLong).contains(accepted);
        }
        Snapshot before = snapshot();
        for (Long actor : List.of(requestingMember, proposingMember)) {
            diagnostic(call(actor, "cancel", pending, "{}"), 409, "MATCHING_009");
            diagnostic(call(actor, "agree-cancel", accepted, "{}"), 409, "MATCHING_009");
            diagnostic(call(actor, "agree-cancel", unilateral, "{}"), 409, "MATCHING_021");
            diagnostic(call(actor, "cancel", accepted, "{}"), 404, "MATCHING_010");
            diagnostic(call(actor, "agree-cancel", mutual, "{}"), 404, "MATCHING_010");
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void P6両側ADMIN_DEPUTYとroleOnlyはnullfalsetrueのcancelに成功する() throws Exception {
        for (List<Long> actors : List.of(requestingAdmins, proposingAdmins)) {
            Long expectedTeam = actors == requestingAdmins ? requestingTeam : proposingTeam;
            for (Long actor : actors) {
                for (String value : List.of("null", "false", "true")) {
                    Long id = fixture(MatchProposalStatus.ACCEPTED, null);
                    assertThat(call(actor, "cancel", id, "{\"mutual\":" + value + "}").getResponse().getStatus()).isEqualTo(200);
                    tx(() -> {
                        MatchProposalEntity proposal = proposals.findById(id).orElseThrow();
                        MatchRequestEntity parent = requests.findById(proposal.getRequestId()).orElseThrow();
                        assertThat(proposal.getStatus()).isEqualTo(MatchProposalStatus.CANCELLED);
                        assertThat(proposal.getCancelledByTeamId()).isEqualTo(expectedTeam);
                        assertThat(proposal.getCancellationType()).isEqualTo(value.equals("true") ? CancellationType.MUTUAL_PENDING : CancellationType.UNILATERAL);
                        assertThat(parent.getStatus()).isEqualTo(MatchRequestStatus.OPEN);
                        assertThat(parent.getMatchedProposalId()).isNull();
                        assertThat(parent.getCancelCount()).isEqualTo((short) 2);
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void P7相手側合意成功と自分の合意400と両側roleの優先を維持する() throws Exception {
        for (List<Long> actors : List.of(requestingAdmins, proposingAdmins)) {
            Long canceller = actors == requestingAdmins ? proposingTeam : requestingTeam;
            for (Long actor : actors) {
                Long id = fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING, canceller, false, false);
                assertAgreed(actor, id);
            }
        }
        Long ownCancel = fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING);
        Snapshot before = snapshot();
        diagnostic(call(requestingAdmins.get(0), "agree-cancel", ownCancel, "{}"), 400, "MATCHING_022");
        assertThat(snapshot()).isEqualTo(before);
        assertAgreed(dualAdmin, fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING));
        Long accepted = fixture(MatchProposalStatus.ACCEPTED, null);
        assertThat(call(dualAdmin, "cancel", accepted, "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(tx(() -> proposals.findById(accepted).orElseThrow().getCancelledByTeamId())).isEqualTo(requestingTeam);
    }

    @Test
    void P8SYSTEMは状態readerだがscopeADMINを持つときだけ変更できる() throws Exception {
        Long pending = fixture(MatchProposalStatus.PENDING, null);
        Long accepted = fixture(MatchProposalStatus.ACCEPTED, null);
        Long unilateral = fixture(MatchProposalStatus.CANCELLED, CancellationType.UNILATERAL);
        Long mutual = fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING);
        Snapshot before = snapshot();
        diagnostic(call(system, "cancel", pending, "{}"), 409, "MATCHING_009");
        diagnostic(call(system, "agree-cancel", unilateral, "{}"), 409, "MATCHING_021");
        diagnostic(call(system, "cancel", accepted, "{}"), 404, "MATCHING_010");
        diagnostic(call(system, "agree-cancel", mutual, "{}"), 404, "MATCHING_010");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(call(systemAdmin, "cancel", accepted, "{}").getResponse().getStatus()).isEqualTo(200);
        assertAgreed(systemAdmin, mutual);
    }

    @Test
    void P9親不在と論理削除は非readerを秘匿し応募側readerとSYSTEMの既存診断を維持する() throws Exception {
        // test profileはddl-auto=create / Flyway無効で、scalar FKは生成されない。
        // 自所有childを先に削除して本番ON DELETE CASCADE後の状態だけをモデル化する。
        // FK自体の実行検証ではなく、productionでは不可能な孤児行も作らない。
        Long removedId = fixture(MatchProposalStatus.ACCEPTED, null, requestingTeam, true, false);
        tx(() -> {
            em.clear();
            assertThat(proposals.findById(removedId)).isEmpty();
            assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM match_proposals WHERE id=:id")
                    .setParameter("id", removedId).getSingleResult()).longValue()).isZero();
            assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM match_requests WHERE id=:id")
                    .setParameter("id", requestIds.get(requestIds.size() - 1)).getSingleResult()).longValue()).isZero();
            return null;
        });
        Snapshot removedBefore = snapshot();
        for (Long actor : userIds) {
            for (String endpoint : List.of("cancel", "agree-cancel")) {
                sameNotFound(call(actor, endpoint, MISSING, "{}"), call(actor, endpoint, removedId, "{}"));
            }
        }
        assertThat(snapshot()).isEqualTo(removedBefore);
        for (MatchProposalStatus state : MatchProposalStatus.values()) {
            for (CancellationType type : cancellationTypes()) {
                Long id = fixture(state, type, requestingTeam, false, true);
                tx(() -> {
                    em.clear();
                    MatchProposalEntity child = proposals.findById(id).orElseThrow();
                    assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM match_proposals WHERE id=:id")
                            .setParameter("id", id).getSingleResult()).longValue()).isEqualTo(1);
                    assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM match_requests WHERE id=:id AND deleted_at IS NOT NULL")
                            .setParameter("id", child.getRequestId()).getSingleResult()).longValue()).isEqualTo(1);
                    assertThat(requests.findById(child.getRequestId())).isEmpty();
                    return null;
                });
                Snapshot before = snapshot();
                for (String endpoint : List.of("cancel", "agree-cancel")) {
                    for (Long actor : List.of(outsider, foreignAdmin, requestingMember, requestingAdmins.get(0))) {
                        sameNotFound(call(actor, endpoint, MISSING, "{}"), call(actor, endpoint, id, "{}"));
                    }
                    for (Long actor : List.of(proposingMember, proposingAdmins.get(0), system)) {
                        String code = state != (endpoint.equals("cancel") ? MatchProposalStatus.ACCEPTED : MatchProposalStatus.CANCELLED)
                                ? "MATCHING_009" : endpoint.equals("agree-cancel") && type != CancellationType.MUTUAL_PENDING
                                ? "MATCHING_021" : "MATCHING_001";
                        diagnostic(call(actor, endpoint, id, "{}"), code.equals("MATCHING_001") ? 404 : 409, code);
                    }
                }
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @Test
    void P11_P12不在境界と型bodyと未認証は拒否してDB不変() throws Exception {
        Long id = fixture(MatchProposalStatus.ACCEPTED, null);
        Snapshot before = snapshot();
        for (String endpoint : List.of("cancel", "agree-cancel")) {
            MvcResult missing = call(outsider, endpoint, MISSING, "{}");
            for (long absent : List.of(0L, -1L)) sameNotFound(missing, call(outsider, endpoint, absent, "{}"));
            diagnostic(http(outsider, patch(path(endpoint, "not-a-number")).contentType(MediaType.APPLICATION_JSON).content("{}")), 400, "COMMON_001");
            assertThat(mvc.perform(patch(path(endpoint, id.toString())).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        diagnostic(call(requestingAdmins.get(0), "cancel", id, "{\"reason\":\"" + "あ".repeat(501) + "\"}"), 400, "COMMON_001");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 同時cancelは成功1件と009で増分1だけになる() throws Exception {
        Long id = fixture(MatchProposalStatus.ACCEPTED, null);
        List<MvcResult> results = simultaneous(id, "cancel", requestingAdmins.get(0), "{\"mutual\":true}");
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(200, 409);
        diagnostic(results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow(), 409, "MATCHING_009");
        tx(() -> {
            MatchProposalEntity proposal = proposals.findById(id).orElseThrow();
            assertThat(proposal.getStatus()).isEqualTo(MatchProposalStatus.CANCELLED);
            assertThat(proposal.getCancellationType()).isEqualTo(CancellationType.MUTUAL_PENDING);
            assertThat(requests.findById(proposal.getRequestId()).orElseThrow().getCancelCount()).isEqualTo((short) 2);
            return null;
        });
    }

    @Test
    void 同時agreeは成功1件と021で減分1だけになる() throws Exception {
        Long id = fixture(MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING);
        List<MvcResult> results = simultaneous(id, "agree-cancel", proposingAdmins.get(0), "{}");
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(200, 409);
        diagnostic(results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow(), 409, "MATCHING_021");
        tx(() -> {
            MatchProposalEntity proposal = proposals.findById(id).orElseThrow();
            assertThat(proposal.getCancellationType()).isEqualTo(CancellationType.MUTUAL);
            assertThat(proposal.getMutualAgreedAt()).isNotNull();
            assertThat(requests.findById(proposal.getRequestId()).orElseThrow().getCancelCount()).isZero();
            return null;
        });
    }

    @Test
    void ACL外側TXなしWriter内TXあり途中2行更新はrollbackする() throws Exception {
        synchronized (MatchProposalCancelScopeContractIT.class) {
            Long id = fixture(MatchProposalStatus.ACCEPTED, null);
            Long parentId = tx(() -> proposals.findById(id).orElseThrow().getRequestId());
            Long actor = requestingAdmins.get(0);
            Snapshot before = snapshot();
            Advised acl = editableProxy(accessControl);
            Advised writer = editableProxy(cancellationWriter);
            Advised repository = editableProxy(requests);
            AtomicInteger aclCalls = new AtomicInteger(), writerCalls = new AtomicInteger(), failures = new AtomicInteger();
            long testThread = Thread.currentThread().threadId();
            DefaultPointcutAdvisor aclProbe = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                if (Thread.currentThread().threadId() == testThread && invocation.getArguments().length > 0
                        && actor.equals(invocation.getArguments()[0])) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    aclCalls.incrementAndGet();
                }
                return invocation.proceed();
            });
            DefaultPointcutAdvisor writerProbe = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                if (Thread.currentThread().threadId() == testThread && ownsWriterInvocation(invocation.getArguments(), id)) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    writerCalls.incrementAndGet();
                }
                return invocation.proceed();
            });
            DefaultPointcutAdvisor rollback = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (Thread.currentThread().threadId() == testThread && invocation.getMethod().getName().equals("save")
                        && invocation.getArguments()[0] instanceof MatchRequestEntity parent && parentId.equals(parent.getId())) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    em.flush();
                    assertThat(em.createNativeQuery("SELECT status FROM match_proposals WHERE id=:id")
                            .setParameter("id", id).getSingleResult().toString()).isEqualTo("CANCELLED");
                    assertThat(((Number) em.createNativeQuery("SELECT cancel_count FROM match_requests WHERE id=:id")
                            .setParameter("id", parentId).getSingleResult()).intValue()).isEqualTo(2);
                    failures.incrementAndGet();
                    throw new IllegalStateException("CMP1027 fixture限定の2行flush後失敗");
                }
                return result;
            });
            // ACLのTX advisorより外側、WriterのTX advisorより内側をそれぞれ観測する。
            acl.addAdvisor(0, aclProbe);
            try {
                writer.addAdvisor(writerProbe);
                try {
                    repository.addAdvisor(0, rollback);
                    try {
                        assertThat(call(actor, "cancel", id, "{}").getResponse().getStatus()).isEqualTo(500);
                        assertThat(aclCalls.get()).isPositive();
                        assertThat(writerCalls.get()).isEqualTo(1);
                        assertThat(failures.get()).isEqualTo(1);
                        assertThat(snapshot()).isEqualTo(before);
                    } finally { assertThat(repository.removeAdvisor(rollback)).isTrue(); }
                } finally { assertThat(writer.removeAdvisor(writerProbe)).isTrue(); }
            } finally {
                try { assertThat(acl.removeAdvisor(aclProbe)).isTrue(); }
                finally { SecurityContextHolder.clearContext(); }
            }
        }
    }

    private Advised editableProxy(Object bean) {
        assertThat(bean).isInstanceOf(Advised.class);
        Advised proxy = (Advised) bean;
        assertThat(proxy.isFrozen()).isFalse();
        assertThat(Arrays.stream(proxy.getAdvisors()).anyMatch(a -> a.getAdvice() instanceof TransactionInterceptor)).isTrue();
        return proxy;
    }

    private boolean ownsWriterInvocation(Object[] arguments, Long id) {
        return arguments.length > 0 && arguments[0] instanceof MatchProposalCancellationWriter.Context context
                && id.equals(context.proposalId());
    }

    private List<MvcResult> simultaneous(Long id, String endpoint, Long actor, String body) throws Exception {
        synchronized (MatchProposalCancelScopeContractIT.class) {
            Advised writer = editableProxy(cancellationWriter);
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger arrived = new AtomicInteger();
            java.util.Set<Long> workerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
            DefaultPointcutAdvisor rendezvous = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                if (workerThreads.contains(Thread.currentThread().threadId()) && ownsWriterInvocation(invocation.getArguments(), id)) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    arrived.incrementAndGet();
                    barrier.await(20, TimeUnit.SECONDS);
                }
                return invocation.proceed();
            });
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<Future<MvcResult>> futures = new ArrayList<>();
            writer.addAdvisor(0, rendezvous);
            try {
                for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                    workerThreads.add(Thread.currentThread().threadId());
                    try { return call(actor, endpoint, id, body); }
                    finally {
                        try { SecurityContextHolder.clearContext(); }
                        finally { workerThreads.remove(Thread.currentThread().threadId()); }
                    }
                }));
                List<MvcResult> results = new ArrayList<>();
                for (Future<MvcResult> future : futures) results.add(future.get(40, TimeUnit.SECONDS));
                assertThat(arrived.get()).isEqualTo(2);
                return results;
            } finally {
                executor.shutdown();
                boolean stopped = executor.awaitTermination(45, TimeUnit.SECONDS);
                if (!stopped) {
                    for (Future<MvcResult> future : futures) future.cancel(true);
                    executor.shutdownNow();
                    stopped = executor.awaitTermination(15, TimeUnit.SECONDS);
                }
                // 全workerが終わるまでadvisorを残し、実行中の他fixtureへ流用しない。
                try {
                    if (!stopped) {
                        unfinishedWorkers = true;
                        throw new IllegalStateException("worker終了未確認: 実行中advisorを除去しない");
                    }
                    assertThat(futures).allMatch(Future::isDone);
                    assertThat(workerThreads).isEmpty();
                    assertThat(writer.removeAdvisor(rendezvous)).isTrue();
                } finally { SecurityContextHolder.clearContext(); }
            }
        }
    }

    private MvcResult http(Long actor, MockHttpServletRequestBuilder request) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return mvc.perform(request.header("Accept-Language", "ja")
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")))).andReturn();
    }
    private MvcResult call(Long actor, String endpoint, long id, String body) throws Exception {
        return http(actor, patch(path(endpoint, Long.toString(id))).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String path(String endpoint, String id) { return "/api/v1/matching/proposals/" + id + "/" + endpoint; }
    private JsonNode error(MvcResult response) throws Exception {
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.has("data")).isFalse();
        assertThat(body.path("error").isObject()).isTrue();
        return body.path("error");
    }
    private void diagnostic(MvcResult response, int status, String code) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(status);
        assertThat(error(response).path("code").asText()).isEqualTo(code);
        assertThat(error(response).path("message").asText()).isNotBlank();
    }
    private void sameNotFound(MvcResult missing, MvcResult existing) throws Exception {
        diagnostic(missing, 404, "MATCHING_002");
        diagnostic(existing, 404, "MATCHING_002");
        assertThat(error(existing)).isEqualTo(error(missing));
        assertThat(error(existing).path("fieldErrors").size()).isZero();
    }
    private List<CancellationType> cancellationTypes() {
        List<CancellationType> types = new ArrayList<>(Arrays.asList(CancellationType.values()));
        types.add(null);
        return types;
    }
    private void assertAgreed(Long actor, Long id) throws Exception {
        assertThat(call(actor, "agree-cancel", id, "{}").getResponse().getStatus()).isEqualTo(200);
        tx(() -> {
            MatchProposalEntity proposal = proposals.findById(id).orElseThrow();
            assertThat(proposal.getCancellationType()).isEqualTo(CancellationType.MUTUAL);
            assertThat(proposal.getMutualAgreedAt()).isNotNull();
            assertThat(requests.findById(proposal.getRequestId()).orElseThrow().getCancelCount()).isZero();
            return null;
        });
    }
    private Long fixture(MatchProposalStatus state, CancellationType type) { return fixture(state, type, requestingTeam, false, false); }
    private Long fixture(MatchProposalStatus state, CancellationType type, Long canceller, boolean physicallyDeleted, boolean deleted) {
        return tx(() -> {
            MatchRequestEntity parent = requests.save(MatchRequestEntity.builder().teamId(requestingTeam)
                    .title("私的応募契約").activityType(ActivityType.values()[0]).prefectureCode("13")
                    .status(MatchRequestStatus.MATCHED).cancelCount((short) 1).build());
            requestIds.add(parent.getId());
            MatchProposalEntity proposal = proposals.save(MatchProposalEntity.builder().requestId(parent.getId())
                    .proposingTeamId(proposingTeam).status(state).cancellationType(type).cancelledByTeamId(canceller).build());
            proposalIds.add(proposal.getId());
            em.flush();
            em.createNativeQuery("UPDATE match_requests SET matched_proposal_id=:pid WHERE id=:id")
                    .setParameter("pid", proposal.getId()).setParameter("id", parent.getId()).executeUpdate();
            if (physicallyDeleted) {
                em.createNativeQuery("DELETE FROM match_proposals WHERE id=:id").setParameter("id", proposal.getId()).executeUpdate();
                em.createNativeQuery("DELETE FROM match_requests WHERE id=:id").setParameter("id", parent.getId()).executeUpdate();
            }
            if (deleted) em.createNativeQuery("UPDATE match_requests SET deleted_at=NOW() WHERE id=:id").setParameter("id", parent.getId()).executeUpdate();
            return proposal.getId();
        });
    }
    private Long user() {
        Long id = users.save(UserEntity.builder().email("proposal-contract-" + UUID.randomUUID() + "@example.com")
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
        return roles.findByName(name).orElseGet(() -> roles.save(RoleEntity.builder().name(name)
                .displayName(name).priority(RolePriority.priority(name)).isSystem(false).build()));
    }
    private void assignRole(Long user, String name, Long team) {
        userRoles.save(UserRoleEntity.builder().userId(user).roleId(role(name).getId()).teamId(team).build());
    }
    private Snapshot snapshot() {
        return tx(() -> {
            em.flush(); em.clear();
            List<?> child = em.createNativeQuery("SELECT * FROM match_proposals WHERE proposing_team_id=:team ORDER BY id").setParameter("team", proposingTeam).getResultList();
            List<?> parent = em.createNativeQuery("SELECT * FROM match_requests WHERE team_id=:team ORDER BY id").setParameter("team", requestingTeam).getResultList();
            return new Snapshot(Arrays.deepToString(child.toArray()), Arrays.deepToString(parent.toArray()), child.size(), parent.size());
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
    private record Snapshot(String proposals, String requests, int proposalCount, int requestCount) { }
    private record FlagState(Long id, Boolean enabled, Long updatedBy, LocalDateTime updatedAt) { }
}
