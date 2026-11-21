package com.mannschaft.app.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 実Service・実MySQL・実commitで開始状態と監査/配信の境界を検査する。
 * 共通MOCK contextを継承し、業務Beanを置換しない。実TCP/HTTP契約はscope試験と実機が担う。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MatchStartTransactionIT extends AbstractMySqlIntegrationTest {
    private static final long ORG = 2611161045L;
    private static final long TEAM = 2611161046L;
    private static final long ACTOR = 2611161047L;
    @Autowired private MatchEventService eventService;
    @Autowired private MatchService matchService;
    @Autowired private MatchRepository matches;
    @Autowired private MatchEventRepository events;
    @Autowired private AuditLogRepository audits;
    @Autowired private AuditLogService auditService;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SimpMessagingTemplate messaging;
    @Autowired private ObjectMapper mapper;
    @Autowired @Qualifier("event-pool") private Executor eventPool;
    private TransactionTemplate tx;
    private UUID matchId;
    private final String auditControlId = UUID.randomUUID().toString();
    private final List<JsonNode> deliveries = new CopyOnWriteArrayList<>();
    private final List<Thread> auditThreads = new CopyOnWriteArrayList<>();
    private final List<Boolean> auditBusinessTransactions = new CopyOnWriteArrayList<>();
    private AbstractMessageChannel channel;
    private ChannelInterceptor interceptor;
    private Advisor auditObserver;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        matchId = tx.execute(s -> matches.saveAndFlush(MatchEntity.builder().organizationId(ORG).teamId(TEAM)
                .sport(Sport.SOCCER).kind(MatchKind.FRIENDLY).homeAway(HomeAway.HOME)
                .status(MatchStatus.SCHEDULED).hasScorekeeper(true).scorekeeperUserId(ACTOR)
                .createdBy(ACTOR).build()).getId());
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 2L, 3L, 4L, 5L);
        channel = (AbstractMessageChannel) messaging.getMessageChannel();
        interceptor = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel target) {
                if (("/topic/matches/" + matchId + "/live").equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                    try {
                        Object payload = message.getPayload();
                        deliveries.add(payload instanceof byte[] bytes ? mapper.readTree(bytes) : mapper.valueToTree(payload));
                    } catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
                return message;
            }
        };
        channel.addInterceptor(interceptor);
        // 既存Async advisorの後段で実処理threadだけを観測し、executor/Bean/contextは置換しない。
        auditObserver = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("record")
                    && invocation.getArguments().length == 9
                    && String.valueOf(invocation.getArguments()[8]).contains(matchId.toString())) {
                auditThreads.add(Thread.currentThread());
                auditBusinessTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            }
            return invocation.proceed();
        });
        ((Advised) auditService).addAdvisor(auditObserver);
    }

    @AfterEach
    void cleanUp() {
        try {
            if (matchId != null) {
                drainEventPool();
                tx.executeWithoutResult(s -> {
                    events.deleteAll(events.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId));
                    audits.deleteAll(audits.findByUserIdOrderByCreatedAtDescIdDesc(ACTOR).stream()
                            .filter(a -> a.getMetadata() != null && (a.getMetadata().contains(matchId.toString())
                                    || a.getMetadata().contains(auditControlId))).toList());
                    matches.deleteById(matchId);
                });
                // observerを残したまま、遅着が所有データを再作成しないことも確認する。
                await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(statusAudits()).isZero();
                    assertThat(controlAudits()).isZero();
                });
            }
        } finally {
            if (channel != null && interceptor != null) channel.removeInterceptor(interceptor);
            if (auditObserver != null) ((Advised) auditService).removeAdvisor(auditObserver);
        }
    }

    private MatchEventService.EventCommand start() {
        return MatchEventService.EventCommand.builder().eventType(MatchEventType.PERIOD_START)
                .period(PeriodType.FIRST_HALF).minute(0).teamSide(TeamSide.HOME).recordedByTeamId(TEAM).build();
    }

    private long statusDeliveries() {
        return deliveries.stream().filter(d -> "STATUS_CHANGED".equals(d.path("type").asText())).count();
    }

    private long statusAudits() {
        return audits.findByUserIdOrderByCreatedAtDescIdDesc(ACTOR).stream()
                .filter(a -> "MATCH_STATUS_CHANGED".equals(a.getEventType()))
                .filter(a -> a.getMetadata() != null && a.getMetadata().contains(matchId.toString())).count();
    }

    private void awaitAudit() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusAudits()).isEqualTo(1));
    }

    private long controlAudits() {
        return audits.findByUserIdOrderByCreatedAtDescIdDesc(ACTOR).stream()
                .filter(a -> a.getMetadata() != null && a.getMetadata().contains(auditControlId)).count();
    }

    /** 実Asyncが停止していない正対照を置き、遅着する余分な監査/配信を連続観測する。 */
    private void assertStableStatusActivity(long expected) {
        auditService.record("MATCH_STATUS_CHANGED", ACTOR, null, TEAM, ORG, null, null, null,
                "{\"controlId\":\"" + auditControlId + "\"}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(controlAudits()).isEqualTo(1));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusAudits()).isEqualTo(expected);
            assertThat(auditThreads).hasSize((int) expected);
            assertThat(statusDeliveries()).isEqualTo(expected);
        });
        drainEventPool();
        assertThat(statusAudits()).isEqualTo(expected);
        assertThat(auditThreads).hasSize((int) expected);
    }

    /** 既存BatchNotificationTransactionITと同じ実poolのidle条件を連続観測する。 */
    private void drainEventPool() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(30)).until(() ->
                pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    @Test
    @DisplayName("STARTとstatusが同commitで保存され、別threadの監査1件とコミット後配信1件へつながる")
    void committedStartIsVisibleAndAuditedOnce() throws Exception {
        Thread caller = Thread.currentThread();
        tx.executeWithoutResult(s -> {
            eventService.record(matchId, ORG, ACTOR, start());
            assertThat(matchService.getMatchOrThrow(matchId, ORG).getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
            assertThat(statusDeliveries()).isZero();
            assertThat(auditThreads).isEmpty();
        });
        assertThat(matches.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, ORG).orElseThrow().getStatus())
                .isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(matches.findTeamMatches(ORG, TEAM, MatchStatus.IN_PROGRESS, null, null, null, null,
                PageRequest.of(0, 10)).getContent()).extracting(MatchEntity::getId).contains(matchId);
        awaitAudit();
        assertThat(statusDeliveries()).isEqualTo(1);
        assertThat(auditThreads).hasSize(1).doesNotContain(caller);
        assertThat(auditBusinessTransactions).containsExactly(false);
        eventService.record(matchId, ORG, ACTOR, start());
        assertStableStatusActivity(1);
    }

    @Test
    @DisplayName("START受理後のtransaction失敗でevent/statusを巻き戻し、監査と配信を残さない")
    void rolledBackStartLeavesNoStatusAuditOrDelivery() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            eventService.record(matchId, ORG, ACTOR, start());
            matches.flush();
            throw new IllegalStateException("開始受理後の故障注入");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(matches.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(events.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).isEmpty();
        assertThat(statusAudits()).isZero();
        assertThat(auditThreads).isEmpty();
        assertThat(deliveries).isEmpty();
        assertStableStatusActivity(0);
    }

    @Test
    @DisplayName("他テナントと記録不可主体のSTARTはevent/statusを変更しない")
    void forbiddenOrForeignStartDoesNotPersist() {
        assertThatThrownBy(() -> eventService.record(matchId, ORG + 1, ACTOR, start())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> eventService.record(matchId, ORG, ACTOR + 1, start())).isInstanceOf(BusinessException.class);
        assertThat(matches.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(events.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).isEmpty();
        assertThat(statusAudits()).isZero();
        assertThat(deliveries).isEmpty();
        assertStableStatusActivity(0);
    }

    @Test
    @DisplayName("同じversionの並行開始は片方が楽観競合し、成功event/status/監査/配信だけを残す")
    void concurrentStartsCommitOneTransition() throws Exception {
        CountDownLatch loaded = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var operation = (java.util.concurrent.Callable<Throwable>) () -> {
                try {
                    tx.executeWithoutResult(s -> {
                        matchService.getMatchOrThrow(matchId, ORG);
                        loaded.countDown();
                        try {
                            if (!loaded.await(10, TimeUnit.SECONDS)) throw new AssertionError("並行読込が揃わない");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        eventService.record(matchId, ORG, ACTOR, start());
                    });
                    return null;
                } catch (ObjectOptimisticLockingFailureException conflict) {
                    return conflict;
                }
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            Throwable one = first.get(30, TimeUnit.SECONDS);
            Throwable two = second.get(30, TimeUnit.SECONDS);
            assertThat(java.util.stream.Stream.of(one, two).filter(e -> e == null).count()).isEqualTo(1);
            assertThat(java.util.stream.Stream.of(one, two).filter(e -> e instanceof ObjectOptimisticLockingFailureException).count()).isEqualTo(1);
            ObjectOptimisticLockingFailureException conflict = (ObjectOptimisticLockingFailureException)
                    (one != null ? one : two);
            assertThat(exceptionHandler.handleOptimisticLock(conflict).getStatusCode().value()).isEqualTo(409);
        }
        awaitAudit();
        assertThat(matches.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(events.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).hasSize(1);
        assertThat(statusDeliveries()).isEqualTo(1);
        assertThat(auditThreads).hasSize(1);
        assertStableStatusActivity(1);
    }
}
