package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R1 の実 MySQL・実 Bean・実 commit を観測する共通 fixture。
 * テストを外側 TX で包まず、通知は AFTER_COMMIT だけを観測する。
 * モックは共通の外部 Redis 境界と外部 Claude API provider だけに限る。
 */
@AutoConfigureMockMvc
@Import(ErrorReportR1TestSupport.Configuration.class)
abstract class ErrorReportR1TestSupport extends AbstractMySqlIntegrationTest {
    @Autowired protected ErrorReportService service;
    @Autowired protected ErrorReportAsyncExecutor backend;
    @Autowired protected ErrorReportAggregator aggregator;
    @Autowired protected ErrorReportRepository reports;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected Control hook;
    @Autowired protected Events events;
    @Autowired protected com.mannschaft.app.errorreport.ErrorReportProperties r1Properties;
    @Autowired protected org.springframework.context.ApplicationContext context;
    private boolean aiEnabled;
    @MockitoBean protected ErrorReportClaudeAiProvider aiProvider;

    protected final List<Long> owned = new CopyOnWriteArrayList<>();
    protected final java.util.Set<String> trackedHashes = ConcurrentHashMap.newKeySet();
    protected final Map<String, AtomicInteger> redisCounts = new ConcurrentHashMap<>();
    protected final List<Boolean> redisTxStates = new CopyOnWriteArrayList<>();
    protected final java.util.Set<String> requestIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepareR1Fixture() {
        // ddl-auto=create はFlyway V12.006の索引を再現しないため、本番同等の非一意hash索引だけ補う。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'error_reports' AND column_name = 'error_hash' AND seq_in_index = 1", Integer.class) == 0) {
            jdbc.execute("CREATE INDEX idx_error_reports_error_hash ON error_reports(error_hash)");
        }
        aiEnabled = r1Properties.getAi().isEnabled();
        hook.reset();
        events.committed.clear();
        aggregator.drainAndClear();
        // AI予算の外部Valkey境界。未作成キーのget=nullを予算0として扱う実サービス契約を通す。
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        doReturn(values).when(redisTemplate).opsForValue();
        SetOperations<String, String> sets = mock(SetOperations.class);
        doReturn(sets).when(redisTemplate).opsForSet();
        when(sets.add(anyString(), any(String[].class))).thenAnswer(call -> {
            redisTxStates.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return 1L;
        });
        when(sets.size(anyString())).thenAnswer(call -> {
            redisTxStates.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return 1L;
        });
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(List.class), any(Object[].class))).thenAnswer(call -> {
                    List<?> keys = call.getArgument(1);
                    return (long) redisCounts.computeIfAbsent(keys.getFirst().toString(),
                            key -> new AtomicInteger()).incrementAndGet();
                });
        when(aiProvider.analyze(any())).thenReturn(AiAnalysisResult.builder()
                .estimatedCause("試練の外部AI応答").fixProposal("試練")
                .impactAssessment("試練").suggestedFiles(List.of()).build());
    }

    @AfterEach
    void cleanR1Fixture() {
        awaitR1AsyncIdle();
        r1Properties.getAi().setEnabled(aiEnabled);
        hook.reset();
        aggregator.drainAndClear();
        for (String requestId : requestIds) {
            jdbc.queryForList("SELECT id FROM error_reports WHERE request_id = ?", Long.class, requestId).forEach(owned::add);
        }
        for (String hash : trackedHashes) {
            jdbc.queryForList("SELECT id FROM error_reports WHERE error_hash = ?", Long.class, hash).forEach(owned::add);
        }
        for (Long id : owned) {
            jdbc.update("DELETE FROM error_report_activities WHERE error_report_id = ?", id);
            jdbc.update("DELETE FROM error_report_occurrences WHERE error_report_id = ?", id);
            jdbc.update("DELETE FROM error_report_ai_analyses WHERE error_report_id = ?", id);
            jdbc.update("DELETE FROM error_reports WHERE id = ?", id);
        }
        owned.clear();
    }

    protected void awaitR1AsyncIdle() {
        org.awaitility.Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(30)).until(() ->
                java.util.stream.Stream.of("event-pool", "ai-analysis-pool").allMatch(name -> {
                    var pool = context.getBean(name, org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
                    return pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty();
                }));
    }

    protected String requestId() {
        String id = UUID.randomUUID().toString(); requestIds.add(id); return id;
    }

    protected ErrorReportRequest request() {
        return ErrorReportRequest.builder().errorMessage("R1 acceptance")
                .pageUrl("/cmp-r1/" + UUID.randomUUID()).occurredAt(LocalDateTime.now()).build();
    }

    protected ErrorReportEntity fixture(String hash, ErrorReportStatus status) {
        trackedHashes.add(hash);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ErrorReportEntity saved = tx.execute(s -> reports.saveAndFlush(ErrorReportEntity.builder()
                .errorMessage("R1 acceptance").pageUrl("/cmp-r1/fixture")
                .occurredAt(now).firstOccurredAt(now).lastOccurredAt(now)
                .errorHash(hash).status(status).severity(ErrorReportSeverity.HIGH)
                .occurrenceCount(1).affectedUserCount(7)
                .workflowStage(ErrorReportWorkflowStage.FIX_IN_PROGRESS).assigneeId(991231L)
                .slaDueAt(now.minusDays(1)).build()));
        owned.add(saved.getId());
        return saved;
    }

    protected ErrorReportEntity fe(ErrorReportRequest request) {
        trackedHashes.add(feHash(request));
        ErrorReportEntity saved = service.createOrAggregate(request, "127.0.0.211");
        owned.add(saved.getId());
        return saved;
    }

    protected String feHash(ErrorReportRequest request) {
        return sha256(request.getErrorMessage() + "|" + request.getPageUrl());
    }

    protected static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected int count(String hash) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM error_reports WHERE error_hash = ? "
                + "AND status <> 'IGNORED'", Integer.class, hash);
    }

    protected int occurrences(String hash) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(occurrence_count),0) FROM error_reports "
                + "WHERE error_hash = ? AND status <> 'IGNORED'", Integer.class, hash);
    }

    protected ErrorReportEntity reload(Long id) {
        return tx.execute(s -> reports.findById(id).orElseThrow());
    }

    protected List<Outcome> parallel(int amount, Callable<?> action) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(amount)) {
            var jobs = new ArrayList<java.util.concurrent.Future<Outcome>>();
            for (int i = 0; i < amount; i++) {
                jobs.add(executor.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    try { return new Outcome(action.call(), null); }
                    catch (Throwable ex) { return new Outcome(null, ex); }
                }));
            }
            start.countDown();
            var result = new ArrayList<Outcome>();
            for (var job : jobs) result.add(job.get(90, TimeUnit.SECONDS));
            return result;
        }
    }

    /** 例外を成功へ読み替えず、並行実行の結果を呼出側でまとめて検証する。 */
    protected record Outcome(Object value, Throwable failure) { }

    /** 本番no-opを置き換える試験用同期点。DBや業務Beanのモックではない。 */
    static class Control extends ErrorReportIngestHook {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger managementLocks = new AtomicInteger();
        volatile BiConsumer<String, Boolean> onRead = (hash, found) -> { };
        volatile Consumer<String> onFlush = hash -> { };
        volatile Consumer<String> onManagement = hash -> { };
        @Override public void afterLockingRead(String hash, boolean found) {
            reads.incrementAndGet(); onRead.accept(hash, found);
        }
        @Override public void afterFlush(String hash) {
            flushes.incrementAndGet(); onFlush.accept(hash);
        }
        @Override public void afterManagementLock(String hash) {
            managementLocks.incrementAndGet(); onManagement.accept(hash);
        }
        void reset() {
            reads.set(0); flushes.set(0); managementLocks.set(0);
            onRead = (hash, found) -> { }; onFlush = hash -> { }; onManagement = hash -> { };
        }
    }

    /** コミット済みドメインイベントだけを保持する。 */
    static class Events {
        final List<Object> committed = new CopyOnWriteArrayList<>();
        @TransactionalEventListener public void raised(ErrorReportRaisedEvent event) { committed.add(event); }
        @TransactionalEventListener public void regression(ErrorReportRegressionDetectedEvent event) { committed.add(event); }
        @TransactionalEventListener public void escalated(ErrorReportSeverityEscalatedEvent event) { committed.add(event); }
    }

    /** 全R1 ITで同一構成を共有する。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary Control r1Control() { return new Control(); }
        @Bean Events r1Events() { return new Events(); }
    }
}
