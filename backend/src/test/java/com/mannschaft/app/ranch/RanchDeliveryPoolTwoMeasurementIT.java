package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.cms.dto.BlogRanchRewardPayload;
import com.mannschaft.app.cms.repository.BlogRanchTransportRepository;
import com.mannschaft.app.cms.service.BlogContentFingerprintService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeasedEvent;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardDeliveryOrchestrator;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardConsumer;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchPolicyPublicationWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.reflection.repository.ReflectionRanchTransportRepository;
import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;
import com.mannschaft.app.schedule.repository.ScheduleRanchTransportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository;
import com.mannschaft.app.timeline.service.TimelineContentFingerprintService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.sql.SQLTransientConnectionException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TEST_ONLY一点入力の実P2配送測定候補。合成資格・transportであり本体活動/UI/SLOの証拠ではない。 */
@ActiveProfiles({"test", "ranch-isolated"})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(RanchDeliveryPoolTwoMeasurementIT.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RanchDeliveryPoolTwoMeasurementIT extends AbstractMySqlIntegrationTest {
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 12;
    @Autowired UserRepository users;
    @Autowired RanchEnrollmentWriter enrollment;
    @Autowired RanchPolicyPublicationWriter publication;
    @Autowired RanchRewardPolicyRepository policies;
    @Autowired RanchAdminCommandRepository commands;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired RanchOwnerRepository owners;
    @Autowired RanchRewardDecisionRepository decisions;
    @Autowired RanchPointLedgerRepository ledger;
    @Autowired RanchPurgeService purge;
    @Autowired RanchRewardDeliveryOrchestrator orchestrator;
    @Autowired RanchRewardConsumer consumer;
    @Autowired UserRewardDeliveryGuard guard;
    @Autowired List<SourceOutboxDeliveryFacade> sources;
    @Autowired TimelineRanchTransportRepository timeline;
    @Autowired BlogRanchTransportRepository blog;
    @Autowired ScheduleRanchTransportRepository schedule;
    @Autowired ReflectionRanchTransportRepository reflection;
    @Autowired TimelineContentFingerprintService timelineFingerprints;
    @Autowired BlogContentFingerprintService blogFingerprints;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TimingAspect timing;
    private Long actor;
    private Long recipient;
    private UUID policy;
    private UUID policyKey;
    private RanchOperationalControlEntity originalControl;
    private boolean controlTouched;
    private long sequence;
    private final EnumMap<RanchRewardSourceType, SourceOutboxDeliveryFacade> byType =
            new EnumMap<>(RanchRewardSourceType.class);
    private RanchDeliveryMeasurementCollector collector;

    @DynamicPropertySource
    static void measurementInputsOnly(DynamicPropertyRegistry properties) {
        properties.add("mannschaft.ranch.development-fixtures", () -> "true");
        properties.add("mannschaft.ranch.delivery.worker.enabled", () -> "false");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        properties.add("spring.datasource.hikari.minimum-idle", () -> "0");
        properties.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        properties.add("app.datasource.replica.enabled", () -> "false");
        for (String source : List.of("timeline", "blog", "schedule", "reflection"))
            properties.add("ranch.source." + source + ".queue-capacity", () -> "0");
        properties.add("mannschaft.ranch.delivery.bounds.version", () -> "TEST_ONLY_NOT_MEASURED");
        Map<String, Integer> inputs = Map.of("batch-size", 10, "lease-seconds", 30,
                "max-attempts", 3, "initial-backoff-seconds", 1, "max-backoff-seconds", 60);
        inputs.forEach((field, value) -> {
            properties.add("mannschaft.ranch.delivery.bounds." + field + ".min", value::toString);
            properties.add("mannschaft.ranch.delivery.bounds." + field + ".max", value::toString);
        });
    }

    @BeforeEach void prepareOnlyOwnedSyntheticTransportFixture() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(2);
        assertThat(policies.count()).as("他政策を消去せず空の専用測定条件を要求する").isZero();
        for (var type : RanchRewardSourceType.values())
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(type), Long.class)).isZero();
        sources.forEach(source -> assertThat(byType.putIfAbsent(source.sourceType(), source)).isNull());
        assertThat(byType).hasSize(RanchRewardSourceType.values().length);
        actor = users.saveAndFlush(RanchTestFixture.user()).getId();
        recipient = users.saveAndFlush(RanchTestFixture.user()).getId();
        Instant now = now();
        originalControl = controls.findById(1).orElse(null);
        controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false)
                .shopEnabled(false).deliveryPaused(true).version(0).createdAt(now).updatedAt(now).build());
        controlTouched = true;
        enrollment.enroll(recipient, UuidV7.generate(), now.minusSeconds(60),
                new RanchStateAssembler.ExternalProjection(false, "DISABLED", false, false, null, null, List.of()));
        policyKey = UuidV7.generate();
        var rules = Arrays.stream(RanchRewardSourceType.values())
                .map(type -> new RanchPolicyPublicationRequest.SourceRule(type, true, "1", 100)).toList();
        var request = new RanchPolicyPublicationRequest(week(now).atStartOfDay(ZoneOffset.UTC).toInstant(),
                true, "5", rules, new RanchPolicyPublicationRequest.Delivery(10, 30, 3, 1, 60), "DEV_ACTIVITY_UI");
        policy = publication.publish(actor, policyKey, request, null, now).response().id();
        var control = controls.findById(1).orElseThrow();
        control.apply(false, false, false, actor, now);
        controls.saveAndFlush(control);
    }

    @AfterEach void cleanupAndWriteFiniteProof() throws Exception {
        timing.collector = null;
        if (collector != null) collector.close();
        boolean cleaned = false;
        try {
            if (recipient != null) {
                tx().executeWithoutResult(ignored -> {
                    timeline.deleteForUser(recipient); blog.deleteForUser(recipient);
                    schedule.deleteForUser(recipient); reflection.deleteForUser(recipient);
                });
                purge.purgeUser(recipient);
            }
            if (actor != null && policyKey != null) commands.findByActorUserIdAndIdempotencyKey(actor, policyKey)
                    .ifPresent(row -> commands.deleteById(row.getId()));
            if (policy != null) policies.deleteById(policy);
            if (controlTouched) {
                if (originalControl == null) controls.deleteById(1); else controls.saveAndFlush(originalControl);
            }
            if (recipient != null) users.deleteById(recipient);
            if (actor != null) users.deleteById(actor);
            cleaned = true;
        } finally {
            if (collector != null) collector.write(json, cleaned);
        }
    }

    @Test void fourRealSourcesHaveBoundedDeliveryTimingAndOneFiniteWeeklyCap() {
        observe(RanchDeliveryMeasurementCollector.Case.FOUR_SOURCES);
        long expected = 0;
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            collector.warmup(round < WARMUP_ROUNDS);
            var committedAt = new EnumMap<RanchRewardSourceType, Long>(RanchRewardSourceType.class);
            for (var type : RanchRewardSourceType.values()) {
                insertSynthetic(type);
                committedAt.put(type, System.nanoTime());
            }
            var result = collector.time(RanchDeliveryMeasurementCollector.Stage.DRAIN, "ALL", orchestrator::drainOnce);
            assertThat(result.leased()).isEqualTo(4);
            assertThat(result.acknowledged()).isEqualTo(4);
            assertThat(result.failed()).isZero();
            assertThat(result.retried()).isZero();
            assertThat(result.deferred()).isZero();
            expected += 4;
            assertThat(decisions.countByUserId(recipient)).isEqualTo(expected);
            assertThat(owners.findByUserId(recipient).orElseThrow().getBalance()).isEqualTo(Math.min(expected, 5));
            for (var type : RanchRewardSourceType.values()) {
                assertThat(count(type, "status='ACKED'")).isEqualTo(round + 1);
                // 完了後のfresh DB再読までを含む上限観測。ACKメソッド返却だけをcommit時刻と推測しない。
                collector.sample(RanchDeliveryMeasurementCollector.Stage.COMMIT_TO_CONFIRMED_ACK,
                        type.name(), System.nanoTime() - committedAt.get(type), false, false, false);
            }
        }
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(recipient)).hasSize(5);
        for (var type : RanchRewardSourceType.values()) {
            for (var stage : List.of(RanchDeliveryMeasurementCollector.Stage.LEASE,
                    RanchDeliveryMeasurementCollector.Stage.CONSUMER,
                    RanchDeliveryMeasurementCollector.Stage.WRITER,
                    RanchDeliveryMeasurementCollector.Stage.ACK))
                collector.requireSamples(stage, type.name(), WARMUP_ROUNDS + MEASURED_ROUNDS);
        }
        collector.requireNoFailedSamples();
        collector.requirePoolBound();
        collector.count(RanchDeliveryMeasurementCollector.Count.ACKED, expected);
        collector.count(RanchDeliveryMeasurementCollector.Count.DECISIONS, decisions.countByUserId(recipient));
        collector.count(RanchDeliveryMeasurementCollector.Count.REWARD_LEDGER, ledger.findByUserIdOrderByOccurredAtDescIdDesc(recipient).size());
        collector.count(RanchDeliveryMeasurementCollector.Count.BALANCE, owners.findByUserId(recipient).orElseThrow().getBalance());
        collector.accepted();
    }

    @Test void savedDecisionSurvivesLostAckAndRealLeaseReclaimWithoutSecondCredit() {
        observe(RanchDeliveryMeasurementCollector.Case.ACK_RECLAIM);
        for (var type : RanchRewardSourceType.values()) {
            var envelope = insertSynthetic(type);
            var source = byType.get(type);
            var first = ownLease(source, envelope.eventId());
            var saved = consumer.consume(first.envelope());
            assertThat(saved.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.AWARDED);
            long balance = owners.findByUserId(recipient).orElseThrow().getBalance();
            long decisionCount = decisions.countByUserId(recipient);
            // ACKを送らない技術fixture。実ACK例外注入の証拠とは区別する。
            assertThat(jdbc.update("UPDATE " + table(type)
                    + " SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))"
                    + " WHERE id=? AND recipient_user_id=? AND status='LEASED' AND lease_token=?",
                    bytes(first.eventId()), recipient, bytes(first.leaseToken()))).isEqualTo(1);
            var second = ownLease(source, envelope.eventId());
            assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
            assertThat(source.acknowledge(new SourceOutboxAckRequest(first.eventId(), first.leaseToken(), now(), saved))).isFalse();
            assertThat(consumer.consume(second.envelope())).isEqualTo(saved);
            assertThat(owners.findByUserId(recipient).orElseThrow().getBalance()).isEqualTo(balance);
            assertThat(decisions.countByUserId(recipient)).isEqualTo(decisionCount);
            assertThat(source.acknowledge(new SourceOutboxAckRequest(second.eventId(), second.leaseToken(), now(), saved))).isTrue();
            assertThat(count(type, "status='ACKED'")).isEqualTo(1);
        }
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(recipient)).hasSize(4);
        collector.requireNoFailedSamples();
        collector.requirePoolBound();
        collector.count(RanchDeliveryMeasurementCollector.Count.STALE_ACK_REJECTED, 4);
        collector.count(RanchDeliveryMeasurementCollector.Count.RECLAIMED, 4);
        collector.count(RanchDeliveryMeasurementCollector.Count.DECISIONS, decisions.countByUserId(recipient));
        collector.count(RanchDeliveryMeasurementCollector.Count.BALANCE, owners.findByUserId(recipient).orElseThrow().getBalance());
        collector.accepted();
    }

    @Test void fullAdmissionDefersWithoutAttemptAndPermitReturnsAfterRealPoolTimeout() throws Exception {
        observe(RanchDeliveryMeasurementCollector.Case.DEFER_ADMISSION);
        for (var type : RanchRewardSourceType.values()) insertSynthetic(type);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> guard.withLockedDeliveryUser(actor, state -> {
                entered.countDown(); await(release); return true;
            }));
            await(entered);
            var result = orchestrator.drainOnce();
            assertThat(result.leased()).isEqualTo(4);
            assertThat(result.deferred()).isEqualTo(4);
            assertThat(result.acknowledged()).isZero();
            assertThat(result.retried()).isZero();
            assertThat(result.failed()).isZero();
            assertThat(decisions.countByUserId(recipient)).isZero();
            assertThat(owners.findByUserId(recipient).orElseThrow().getBalance()).isZero();
            for (var type : RanchRewardSourceType.values())
                assertThat(count(type, "status='RETRY' AND attempt_count=0 AND lease_token IS NULL")).isEqualTo(1);
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> guard.withLockedDeliveryUser(recipient, state -> callbacks.incrementAndGet()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isSameAs(UserOperationErrorCode.UNAVAILABLE));
            assertThat(callbacks).hasValue(0);
            collector.count(RanchDeliveryMeasurementCollector.Count.OVERFLOW_CALLBACKS, callbacks.get());
            collector.count(RanchDeliveryMeasurementCollector.Count.DEFERRED_WITHOUT_ATTEMPT, 4);
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown(); executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        // 意図的な第三接続要求だけを別sampleへ記録。正常配送timeoutとは混ぜない。
        var probeFailure = new IllegalStateException("意図的なprobe後のcallback rollback");
        assertThatThrownBy(() -> guard.withLockedDeliveryUser(actor, state -> tx().execute(ignored -> {
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            long start = System.nanoTime();
            try (var unexpected = dataSource.getConnection()) {
                throw new AssertionError("P2で第三接続を取得しました");
            } catch (SQLTransientConnectionException expected) {
                collector.intentionalPoolTimeout();
                collector.sample(RanchDeliveryMeasurementCollector.Stage.POOL_PROBE, "ALL",
                        System.nanoTime() - start, true, true, false);
            } catch (java.sql.SQLException unexpected) {
                throw new AssertionError("第三接続probeが想定timeout以外で失敗しました");
            }
            throw probeFailure;
        }))).isSameAs(probeFailure);
        assertThat(guard.withLockedDeliveryUser(recipient, state -> state.lifecycle()))
                .isEqualTo(com.mannschaft.app.auth.dto.DeliveryUserState.Lifecycle.ACTIVE);
        for (var type : RanchRewardSourceType.values())
            assertThat(jdbc.update("UPDATE " + table(type)
                    + " SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))"
                    + " WHERE recipient_user_id=? AND status='RETRY' AND attempt_count=0", recipient)).isEqualTo(1);
        var recovered = orchestrator.drainOnce();
        assertThat(recovered.acknowledged()).isEqualTo(4);
        assertThat(recovered.failed()).isZero();
        assertThat(decisions.countByUserId(recipient)).isEqualTo(4);
        collector.requirePoolBound();
        collector.count(RanchDeliveryMeasurementCollector.Count.PERMIT_RECOVERED, 1);
        collector.count(RanchDeliveryMeasurementCollector.Count.ACKED, recovered.acknowledged());
        collector.count(RanchDeliveryMeasurementCollector.Count.DECISIONS, decisions.countByUserId(recipient));
        collector.accepted();
    }

    private void observe(RanchDeliveryMeasurementCollector.Case measurementCase) {
        collector = new RanchDeliveryMeasurementCollector((HikariDataSource) dataSource, measurementCase);
        timing.collector = collector;
    }

    /** native行の存在を捏造しない合成transport前提。源所有repository経由だけで耐久受付する。 */
    private RanchRewardEnvelope insertSynthetic(RanchRewardSourceType type) {
        Instant at = now();
        long id = ++sequence;
        var facts = switch (type) {
            case TIMELINE_ORIGINAL -> (RanchRewardEnvelope.SourceFacts) new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, true);
            case BLOG_FIRST_PUBLISH -> new RanchRewardEnvelope.Blog(RanchRewardEnvelope.PublicationKind.MANUAL, true);
            case ATTENDANCE_RESPONSE -> new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ATTENDING, false, true);
            case PERSONAL_RECALL_COMPLETE -> new RanchRewardEnvelope.PersonalRecall(UuidV7.generate(), 4, week(at), true);
        };
        var origin = switch (type) {
            case TIMELINE_ORIGINAL -> RanchRewardEnvelope.Origin.ORIGINAL;
            case BLOG_FIRST_PUBLISH -> RanchRewardEnvelope.Origin.FIRST_PUBLISH;
            case ATTENDANCE_RESPONSE -> RanchRewardEnvelope.Origin.SELF_RESPONSE;
            case PERSONAL_RECALL_COMPLETE -> RanchRewardEnvelope.Origin.PERSONAL_COMPLETION;
        };
        boolean recall = type == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE;
        var event = new RanchRewardEnvelope(UuidV7.generate(), 1, type,
                recall ? RanchRewardEnvelope.IdType.UUID : RanchRewardEnvelope.IdType.LONG,
                recall ? UuidV7.generate().toString() : Long.toString(id), RanchRewardEnvelope.ScopeType.PERSONAL,
                null, null, RanchRewardEnvelope.ActorKind.USER, recipient, null, recipient, recipient, at, origin, facts);
        collector.time(RanchDeliveryMeasurementCollector.Stage.TRANSPORT, type.name(), () -> tx().execute(ignored -> {
            String encoded;
            try { encoded = json.writeValueAsString(event); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new AssertionError("合成transportの符号化失敗"); }
            switch (type) {
                case TIMELINE_ORIGINAL -> assertThat(timeline.insertQualified(json.convertValue(event, TimelineRanchRewardPayload.class),
                        timelineFingerprints.fingerprint(recipient, at, "合成配送" + id, List.of()), encoded, at))
                        .isEqualTo(TimelineRanchTransportRepository.InsertOutcome.ACCEPTED);
                case BLOG_FIRST_PUBLISH -> assertThat(blog.insertQualified(json.convertValue(event, BlogRanchRewardPayload.class),
                        blogFingerprints.fingerprint(recipient, at, "合成題", "合成配送" + id, List.of()), encoded, at))
                        .isEqualTo(BlogRanchTransportRepository.InsertOutcome.ACCEPTED);
                case ATTENDANCE_RESPONSE -> assertThat(schedule.insertQualified(json.convertValue(event, ScheduleRanchRewardPayload.class), id, encoded, at)).isTrue();
                case PERSONAL_RECALL_COMPLETE -> assertThat(reflection.insertQualified(json.convertValue(event, ReflectionRecallRewardPayload.class), encoded, at)).isTrue();
            }
            return true;
        }));
        return event;
    }

    private SourceOutboxLeasedEvent ownLease(SourceOutboxDeliveryFacade source, UUID event) {
        var leased = source.lease(new SourceOutboxLeaseRequest(now(), 10, 30, 3));
        assertThat(leased).hasSize(1);
        assertThat(leased.getFirst().eventId()).isEqualTo(event);
        return leased.getFirst();
    }

    private long count(RanchRewardSourceType type, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(type)
                + " WHERE recipient_user_id=? AND " + condition, Long.class, recipient);
    }
    private static String table(RanchRewardSourceType type) {
        return switch (type) {
            case TIMELINE_ORIGINAL -> "timeline_ranch_outboxes";
            case BLOG_FIRST_PUBLISH -> "blog_ranch_outboxes";
            case ATTENDANCE_RESPONSE -> "schedule_ranch_outboxes";
            case PERSONAL_RECALL_COMPLETE -> "reflection_ranch_outboxes";
        };
    }
    private TransactionTemplate tx() {
        var result = new TransactionTemplate(transactionManager);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return result;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDate week(Instant at) {
        return at.atOffset(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("有限同期の期限切れ"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("有限同期の中断"); }
    }

    /** 観測だけを追加し、実facade/consumer/writerを差替えない専用context。 */
    @TestConfiguration
    static class ObservationConfiguration {
        @Bean TimingAspect measurementTimingAspect() { return new TimingAspect(); }
    }

    /** advisor順序は未確認のまま表示し、SQLや新接続を観測目的に借りない。 */
    @Aspect
    static class TimingAspect {
        volatile RanchDeliveryMeasurementCollector collector;
        @Around("execution(* com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryFacade+.*(..))")
        Object source(ProceedingJoinPoint call) throws Throwable {
            var target = (SourceOutboxDeliveryFacade) call.getTarget();
            var stage = switch (call.getSignature().getName()) {
                case "lease" -> RanchDeliveryMeasurementCollector.Stage.LEASE;
                case "acknowledge" -> RanchDeliveryMeasurementCollector.Stage.ACK;
                case "defer" -> RanchDeliveryMeasurementCollector.Stage.DEFER;
                case "retry" -> RanchDeliveryMeasurementCollector.Stage.RETRY;
                default -> null;
            };
            return stage == null ? call.proceed() : record(call, stage, target.sourceType().name());
        }
        @Around(value = "execution(* com.mannschaft.app.ranch.reward.RanchRewardConsumerService.consume(..)) && args(event)", argNames = "call,event")
        Object consumer(ProceedingJoinPoint call, RanchRewardEnvelope event) throws Throwable {
            return record(call, RanchDeliveryMeasurementCollector.Stage.CONSUMER, event.sourceType().name());
        }
        @Around(value = "execution(* com.mannschaft.app.ranch.reward.RanchRewardWriter.decide(..)) && args(event)", argNames = "call,event")
        Object writer(ProceedingJoinPoint call, RanchRewardEnvelope event) throws Throwable {
            return record(call, RanchDeliveryMeasurementCollector.Stage.WRITER, event.sourceType().name());
        }
        private Object record(ProceedingJoinPoint call, RanchDeliveryMeasurementCollector.Stage stage, String source) throws Throwable {
            var active = collector;
            if (active == null) return call.proceed();
            long start = System.nanoTime();
            boolean failed = true;
            boolean tx = TransactionSynchronizationManager.isActualTransactionActive();
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            try { Object result = call.proceed(); failed = false; return result; }
            finally { active.sample(stage, source, System.nanoTime() - start, failed, tx, readOnly); }
        }
    }
}
