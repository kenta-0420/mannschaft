package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardDeliveryConfigReader;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.service.RanchPolicyPublicationWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchAdminFacade;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.reward.RanchRewardWriter;
import com.mannschaft.app.ranch.reward.RanchRewardProjectionReader;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実Bean・MySQLの開発政策契約。合成boundは型境界用で実機測定の証拠ではない。 */
@ActiveProfiles({"test", "ranch-isolated"})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchDevelopmentPolicyIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2032-01-06T12:00:00.123456Z");
    private static final Instant CURRENT_WEEK = Instant.parse("2032-01-05T00:00:00Z");
    private static final Instant FUTURE_WEEK = Instant.parse("2032-01-12T00:00:00Z");
    private static final String OVERRIDE = "development-policy-it-only";
    @Autowired RanchPolicyPublicationWriter writer;
    @Autowired RanchRewardDeliveryConfigReader config;
    @Autowired RanchRewardPolicyRepository policies;
    @Autowired RanchAdminCommandRepository commands;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired UserRepository users;
    @Autowired ConfigurableEnvironment environment;
    @Autowired ObjectMapper json;
    @Autowired RanchEnrollmentWriter enrollment;
    @Autowired RanchPurgeService purge;
    @Autowired RanchRewardWriter consumer;
    @Autowired RanchRewardProjectionReader projection;
    @Autowired RanchPointLedgerRepository ledger;
    @Autowired RanchWeekBudgetRepository budgets;
    @Autowired RanchRewardDecisionRepository decisions;
    @Autowired RanchOwnerRepository owners;
    @Autowired RanchAdminFacade facade;
    @PersistenceContext EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    private Long recipient;
    private Long actor;
    private RanchOperationalControlEntity original;
    private boolean controlTouched;
    private final List<UUID> ownPolicies = new ArrayList<>();
    private final List<UUID> ownKeys = new ArrayList<>();

    @DynamicPropertySource
    static void syntheticValidatorBounds(DynamicPropertyRegistry properties) {
        properties.add("mannschaft.ranch.development-fixtures", () -> "true");
        properties.add("mannschaft.ranch.delivery.bounds.version", () -> "TEST_ONLY_NOT_MEASURED");
        for (String field : List.of("batch-size", "lease-seconds", "max-attempts",
                "initial-backoff-seconds", "max-backoff-seconds")) {
            properties.add("mannschaft.ranch.delivery.bounds." + field + ".min", () -> "1");
            properties.add("mannschaft.ranch.delivery.bounds." + field + ".max", () -> "100");
        }
    }

    @BeforeEach
    void prepareOnlyOwnActorAndMigrationEquivalentControl() {
        assertThat(policies.count()).as("この専用contextは初回空policyから検証する").isZero();
        actor = users.saveAndFlush(RanchTestFixture.user()).getId();
        original = controls.findById(1).orElse(null);
        controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false)
                .shopEnabled(false).deliveryPaused(true).version(0).createdAt(NOW).updatedAt(NOW).build());
        controlTouched = true;
    }

    @AfterEach
    void restoreOnlyOwnFixtureRowsAndProperty() {
        environment.getPropertySources().remove(OVERRIDE);
        for (UUID key : ownKeys) commands.findByActorUserIdAndIdempotencyKey(actor, key)
                .ifPresent(row -> commands.deleteById(row.getId()));
        if (recipient != null) purge.purgeUser(recipient);
        ownPolicies.forEach(policies::deleteById);
        if (controlTouched) {
            if (original == null) controls.deleteById(1); else controls.saveAndFlush(original);
        }
        if (actor != null) new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id=:actor")
                    .setParameter("actor", actor).executeUpdate();
            if (recipient != null) users.deleteById(recipient);
            users.deleteById(actor);
            users.flush();
        });
        ownKeys.clear(); ownPolicies.clear();
    }

    @Test
    void isolatedFirstCurrentWeekDevPolicyWorksWithoutFormalApprovalAndReplaySurvivesFlagOff() {
        UUID key = key();
        var request = request(CURRENT_WEEK, true);
        var first = publish(key, request);
        assertThat(first.createdNow()).isTrue();
        assertThat(first.response().settings().reasonCode()).isEqualTo("DEV_ACTIVITY_UI");
        assertThat(first.response().version()).isNotEqualTo("0");
        assertThat(controls.findById(1).orElseThrow().isCareEnabled()).isFalse();
        assertThat(controls.findById(1).orElseThrow().isDeliveryPaused()).isTrue();
        disableFixture();
        var replay = writer.publish(actor, key, request, null, NOW.plusSeconds(1));
        assertThat(replay.createdNow()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
    }

    @Test
    void isolatedFutureDevPolicyHasNoFormalReadinessRequirement() {
        var saved = publish(key(), request(FUTURE_WEEK, true));
        assertThat(saved.response().settings().enabled()).isTrue();
        assertThat(saved.response().settings().reasonCode()).isEqualTo("DEV_ACTIVITY_UI");
    }

    @Test
    void reservedDevPrefixIsRejectedWithFlagOffEvenForDisabledFuturePolicy() {
        disableFixture();
        UUID key = key();
        assertThatThrownBy(() -> writer.publish(actor, key, request(FUTURE_WEEK, false), null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actor, key)).isEmpty();
        assertThat(policies.count()).isZero();
    }

    @Test
    void storedDevPolicyDoesNotExposeDeliveryConfigurationAfterFlagOff() {
        UUID id = UuidV7.generate();
        var sources = new EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        for (var type : RanchRewardSourceType.values()) sources.put(type,
                new RanchRewardPolicySnapshot.SourceRule(true, 1, 5));
        var snapshot = new RanchRewardPolicySnapshot(id, 1, CURRENT_WEEK, true, 100,
                sources, new RanchRewardPolicySnapshot.DeliverySettings(10, 30, 3, 1, 60), "DEV_ACTIVITY_UI");
        var encoded = RanchRewardPolicyCodec.encode(snapshot, json);
        policies.saveAndFlush(RanchRewardPolicyEntity.builder().id(id).versionNumber(1).effectiveAt(CURRENT_WEEK)
                .schemaVersion(1).settingsJson(encoded.json()).contentHash(encoded.sha256())
                .publishedBy(actor).publishedAt(NOW.minusSeconds(1)).build());
        ownPolicies.add(id);
        disableFixture();
        assertThat(config.current(NOW)).isEmpty();
        assertThat(policies.findById(id)).isPresent();
    }

    @Test
    void configuredControlDoesNotDisqualifyEmptyFirstCurrentWeekPublication() {
        var control = controls.findById(1).orElseThrow();
        control.apply(true, true, false, actor, NOW);
        controls.saveAndFlush(control);
        assertThat(publish(key(), request(CURRENT_WEEK, true)).createdNow()).isTrue();
        assertThat(controls.findById(1).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void newCreditAndFrozenProjectionRejectOffModeWhileSavedDecisionReplays() {
        Long userId = enrollRecipient();
        publish(key(), request(CURRENT_WEEK, true));
        enableDelivery();
        var firstEvent = recall(userId);
        var first = consumer.decide(firstEvent);
        assertThat(first.awardedPoints()).isEqualTo(1);
        disableFixture();
        assertThat(consumer.decide(firstEvent)).isEqualTo(first);
        assertThatThrownBy(() -> consumer.decide(recall(userId))).isInstanceOf(IllegalStateException.class);
        assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2032-01-05"))
                .orElseThrow().getAwardedTotal()).isEqualTo(1);
        var view = projection.current(userId, NOW.plusSeconds(2));
        assertThat(view.rewardsStatus()).isEqualTo("DISABLED");
        assertThat(view.weekBudget()).isNull();
        assertThat(view.policyVersion()).isNull();
    }

    /** 実control待機を観測する。接続数や性能の測定の代用にはしない。 */
    @Test
    void concurrentInitialPublicationAndConsumeCannotBothBypassEmptyStore() throws Exception {
        Long userId = enrollRecipient();
        enableDelivery();
        UUID key = key();
        var event = recall(userId);
        var executor = Executors.newFixedThreadPool(2);
        var published = new AtomicReference<Future<RanchPolicyPublicationWriter.PublicationOutcome>>();
        var consumed = new AtomicReference<Future<RanchRewardDeliveryOutcome>>();
        var rejected = new AtomicReference<BusinessException>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                controls.lockSingleton().orElseThrow();
                published.set(executor.submit(() -> {
                    try { return writer.publish(actor, key, request(CURRENT_WEEK, true), null, NOW); }
                    catch (BusinessException failure) { rejected.set(failure); return null; }
                }));
                consumed.set(executor.submit(() -> consumer.decide(event)));
                awaitTwoOwnControlWaiters();
            });
            var result = published.get().get(15, TimeUnit.SECONDS);
            var decision = consumed.get().get(15, TimeUnit.SECONDS);
            if (result != null) {
                ownPolicies.add(result.response().id());
                assertThat(rejected.get()).isNull();
                assertThat(decision.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.AWARDED);
                assertThat(decision.awardedPoints()).isEqualTo(1);
                assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
            } else {
                assertThat(rejected.get()).isNotNull();
                assertThat(rejected.get().getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007);
                assertThat(decision.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.SOURCE_DISABLED);
                assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).isEmpty();
                assertThat(policies.count()).isZero();
            }
            assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            commands.findByActorUserIdAndIdempotencyKey(actor, key).ifPresent(command -> {
                // assert途中の失敗でも、自身が公開したpolicyだけ回収する。
                policies.findTopByOrderByVersionNumberDesc().filter(policy -> actor.equals(policy.getPublishedBy()))
                        .ifPresent(policy -> { if (!ownPolicies.contains(policy.getId())) ownPolicies.add(policy.getId()); });
            });
        }
    }

    private void awaitTwoOwnControlWaiters() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = connection.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                     + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                     + "WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='ranch_operational_controls' "
                     + "AND l.INDEX_NAME='PRIMARY' AND l.LOCK_DATA='1'")) {
            query.setString(1, MYSQL.getDatabaseName());
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getLong(1) >= 2) return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("control行の待機二件を観測できませんでした");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("control待機観測が中断されました");
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("専用MySQLのcontrol待機観測に失敗しました");
        }
    }

    private Long enrollRecipient() {
        recipient = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(recipient, UuidV7.generate(), NOW.minusSeconds(60),
                new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                        false, null, null, List.of()));
        return recipient;
    }

    private void enableDelivery() {
        var control = controls.findById(1).orElseThrow();
        control.apply(false, false, false, actor, NOW);
        controls.saveAndFlush(control);
    }

    private RanchRewardEnvelope recall(Long userId) {
        return new RanchRewardEnvelope(UuidV7.generate(), 1, RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.IdType.UUID, UuidV7.generate().toString(),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null, RanchRewardEnvelope.ActorKind.USER,
                userId, null, userId, userId, NOW.plusSeconds(1), RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(UuidV7.generate(), 4, LocalDate.parse("2032-01-05"), true));
    }

    @Test
    void priorCareLedgerDoesNotPreventFirstCurrentWeekRewardPolicy() {
        Long userId = enrollRecipient();
        insertOwnLedgerBoundaryFixture(userId, "CARE", 0, 1);
        assertThat(publish(key(), request(CURRENT_WEEK, true)).createdNow()).isTrue();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId).get(0).getEntryKind()).isEqualTo("CARE");
    }

    @Test
    void priorRewardLedgerRejectsFirstCurrentWeekPolicyWithoutAddingCommand() {
        Long userId = enrollRecipient();
        insertOwnLedgerBoundaryFixture(userId, "REWARD", 1, 0);
        assertCurrentWeekConflict();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
    }

    @Test
    void existingPolicyRejectsCurrentWeekException() {
        publish(key(), request(FUTURE_WEEK, true));
        assertCurrentWeekConflict();
        assertThat(policies.count()).isEqualTo(1);
    }

    @Test
    void existingZeroDecisionRejectsCurrentWeekException() {
        Long userId = enrollRecipient();
        enableDelivery();
        assertThat(consumer.decide(recall(userId)).outcome())
                .isEqualTo(RanchRewardDeliveryOutcome.Outcome.SOURCE_DISABLED);
        assertCurrentWeekConflict();
        assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2032-01-05"))).isEmpty();
    }

    @Test
    void existingFrozenBudgetRejectsCurrentWeekExceptionWithoutChangingCredit() {
        Long userId = enrollRecipient();
        publish(key(), request(CURRENT_WEEK, true));
        enableDelivery();
        assertThat(consumer.decide(recall(userId)).awardedPoints()).isEqualTo(1);
        assertCurrentWeekConflict();
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2032-01-05"))
                .orElseThrow().getAwardedTotal()).isEqualTo(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
    }

    @Test
    void formalEnabledPolicyStillRequiresFormalReadinessInFixtureMode() {
        var dev = request(FUTURE_WEEK, true);
        var formal = new RanchPolicyPublicationRequest(dev.effectiveAt(), dev.enabled(), dev.globalWeeklyCap(),
                dev.sources(), dev.delivery(), "FORMAL_ACTIVITY");
        UUID key = key();
        assertThatThrownBy(() -> writer.publish(actor, key, formal, null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actor, key)).isEmpty();
    }

    private void assertCurrentWeekConflict() {
        UUID key = key();
        assertThatThrownBy(() -> writer.publish(actor, key, request(CURRENT_WEEK, true), null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actor, key)).isEmpty();
    }

    /** 空条件だけの人工DB fixture。通常育成や実workerの成功証拠として数えない。 */
    private void insertOwnLedgerBoundaryFixture(Long userId, String kind, long points, long xp) {
        var owner = owners.findByUserId(userId).orElseThrow();
        ledger.saveAndFlush(RanchPointLedgerEntity.builder().ownerId(owner.getId()).userId(userId)
                .entryKind(kind).deltaPoints(points).balanceAfter(points).deltaXp(xp)
                .ruleSnapshot("{}").occurredAt(NOW).createdAt(NOW).build());
    }

    @Test
    void realAdminFacadeAcceptsDevButRejectsOrdinaryActorAndKeepsSavedAckOffMode() {
        com.fasterxml.jackson.databind.JsonNode body = json.valueToTree(request(FUTURE_WEEK, true));
        UUID rejectedKey = key();
        assertThatThrownBy(() -> facade.publishPolicy(actor, rejectedKey, body))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actor, rejectedKey)).isEmpty();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null));
        UUID key = key();
        var first = facade.publishPolicy(actor, key, body);
        ownPolicies.add(first.response().id());
        assertThat(first.createdNow()).isTrue();
        assertThat(first.response().settings().reasonCode()).isEqualTo("DEV_ACTIVITY_UI");
        assertThat(owners.findByUserId(actor)).isEmpty();
        disableFixture();
        var saved = facade.publishPolicy(actor, key, body);
        assertThat(saved.createdNow()).isFalse();
        assertThat(saved.response()).isEqualTo(first.response());
    }

    private void disableFixture() {
        environment.getPropertySources().addFirst(new MapPropertySource(OVERRIDE,
                Map.of("mannschaft.ranch.development-fixtures", "false")));
    }

    private UUID key() { UUID value = UuidV7.generate(); ownKeys.add(value); return value; }
    private RanchPolicyPublicationWriter.PublicationOutcome publish(UUID key, RanchPolicyPublicationRequest request) {
        var value = writer.publish(actor, key, request, null, NOW);
        if (value.createdNow()) ownPolicies.add(value.response().id());
        return value;
    }
    private RanchPolicyPublicationRequest request(Instant at, boolean enabled) {
        var sources = Arrays.stream(RanchRewardSourceType.values())
                .map(type -> new RanchPolicyPublicationRequest.SourceRule(type, enabled, "1", 5)).toList();
        return new RanchPolicyPublicationRequest(at, enabled, "100", sources,
                new RanchPolicyPublicationRequest.Delivery(10, 30, 3, 1, 60), "DEV_ACTIVITY_UI");
    }
}
