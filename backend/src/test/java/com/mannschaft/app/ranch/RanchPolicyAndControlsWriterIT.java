package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.service.RanchOperationalControlsWriter;
import com.mannschaft.app.ranch.service.RanchPolicyPublicationWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLの保存ACK・競合・停止期間。fresh管理者HTTP認可の試験とは分ける。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchPolicyAndControlsWriterIT extends AbstractMySqlIntegrationTest {
    @Autowired RanchPolicyPublicationWriter policiesWriter;
    @Autowired RanchOperationalControlsWriter controlsWriter;
    @Autowired RanchRewardPolicyRepository policies;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired RanchRewardPausePeriodRepository pauses;
    @Autowired RanchAdminCommandRepository commands;
    @Autowired UserRepository users;
    private Long actorId;
    private RanchOperationalControlEntity initialControl;
    private final List<UUID> ownKeys = new CopyOnWriteArrayList<>();
    private final List<UUID> ownPolicies = new CopyOnWriteArrayList<>();
    private final List<UUID> ownPauses = new CopyOnWriteArrayList<>();
    private final Instant now = Instant.parse("2026-10-05T00:00:00.123456Z");

    @BeforeEach
    void prepareOnlySyntheticActorAndMigrationEquivalentControl() {
        actorId = users.saveAndFlush(RanchTestFixture.user()).getId();
        initialControl = controls.findById(1).orElseGet(() -> RanchOperationalControlEntity.builder()
                .id(1).careEnabled(false).shopEnabled(false).deliveryPaused(true).version(0)
                .createdAt(now).updatedAt(now).build());
        // テストprofileではFlyway seedを実行しない。productionの自動作成ではない。
        controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false).shopEnabled(false)
                .deliveryPaused(true).version(0).createdAt(initialControl.getCreatedAt()).updatedAt(now).build());
    }

    @AfterEach
    void removeOnlyOwnedRowsAndRestoreFixtureControl() {
        for (UUID key : ownKeys) commands.findByActorUserIdAndIdempotencyKey(actorId, key)
                .ifPresent(command -> commands.deleteById(command.getId()));
        ownPolicies.forEach(policies::deleteById);
        ownPauses.forEach(pauses::deleteById);
        if (initialControl != null) controls.saveAndFlush(initialControl);
        ownKeys.clear(); ownPolicies.clear(); ownPauses.clear();
    }

    @Test
    void disabledPolicyReplayKeepsCanonicalVersionAndRejectsChangedBodyAfterEffectiveWeek() {
        UUID key = key();
        var request = policy("2032-01-05T00:00:00Z", false, "100");
        var first = publish(key, request);
        var replay = policiesWriter.publish(actorId, key, request, null, Instant.parse("2032-01-06T00:00:00Z"));
        assertThat(replay.createdNow()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(first.response().settings().enabled()).isFalse();
        assertThat(policies.findById(first.response().id()).orElseThrow().getSettingsJson())
                .contains("\"enabled\":false", "\"delivery\"", "\"sources\"");
        assertThatThrownBy(() -> policiesWriter.publish(actorId, key,
                policy("2032-01-05T00:00:00Z", false, "101"), null, now))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_003));
    }

    @Test
    void enabledPolicyWithoutReadinessIsDefinitiveRejectionBeforeAnyCommandOrPolicyWrite() {
        UUID key = key();
        var request = policy("2032-01-12T00:00:00Z", true, "100");
        assertThatThrownBy(() -> policiesWriter.publish(actorId, key, request, null, now))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key)).isEmpty();
        assertThat(policies.existsByEffectiveAt(request.effectiveAt())).isFalse();
    }

    @Test
    void simultaneousSameKeyPolicyHasOneCreationAndIdenticalAck() throws Exception {
        UUID key = key();
        var request = policy("2032-01-19T00:00:00Z", false, "100");
        var results = pair(() -> publish(key, request), () -> publish(key, request));
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.stream().filter(result -> result.success().createdNow())).hasSize(1);
        assertThat(results.get(0).success().response()).isEqualTo(results.get(1).success().response());
    }

    @Test
    void simultaneousDifferentKeysAtSameEffectiveWeekHaveOnlyOnePublication() throws Exception {
        var request = policy("2032-01-26T00:00:00Z", false, "100");
        UUID left = key(), right = key();
        var results = pair(() -> publish(left, request), () -> publish(right, request));
        assertThat(results.stream().filter(result -> result.success() != null)).hasSize(1);
        assertThat(results.stream().filter(result -> result.failure() != null)).singleElement()
                .satisfies(result -> assertThat(result.failure().getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007));
    }

    @Test
    void deliveryControlChangesDoNotRequirePublicMasterAndRewardPauseUsesOccurrenceInterval() {
        UUID firstKey = key();
        var first = controlsWriter.update(actorId, firstKey, control("0", false, true), null, now);
        // repositoryのlock queryはwriter取引内だけ。試験では自分のactorの保存行を照会する。
        var owned = pauses.findAll().stream().filter(period -> actorId.equals(period.getChangedBy())).toList();
        assertThat(owned).hasSize(1);
        ownPauses.add(owned.get(0).getId());
        assertThat(first.isDeliveryPaused()).isFalse();
        assertThat(first.isRewardsPaused()).isTrue();
        assertThat(pauses.includes(now.minusNanos(1000))).isFalse();
        assertThat(pauses.includes(now)).isTrue();
        Instant end = now.plusSeconds(1);
        var second = controlsWriter.update(actorId, key(), control("1", true, false), null, end);
        assertThat(second.isDeliveryPaused()).isTrue();
        assertThat(second.isRewardsPaused()).isFalse();
        assertThat(pauses.includes(end.minusNanos(1000))).isTrue();
        assertThat(pauses.includes(end)).isFalse();
        assertThat(controlsWriter.update(actorId, firstKey, control("0", false, true), null, end.plusSeconds(1)))
                .isEqualTo(first);
        assertThat(controls.findById(1).orElseThrow().getVersion()).isEqualTo(2);
    }

    @Test
    void careOpeningWithoutReadinessDoesNotWriteControlOrAck() {
        UUID key = key();
        var request = new RanchOperationalControlsRequest("0", true, false, false, false, "CONTROL_TEST");
        assertThatThrownBy(() -> controlsWriter.update(actorId, key, request, null, now))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key)).isEmpty();
        var current = controls.findById(1).orElseThrow();
        assertThat(current.getVersion()).isZero();
        assertThat(current.isCareEnabled()).isFalse();
        assertThat(current.isDeliveryPaused()).isTrue();
    }

    @Test
    void staleControlVersionDoesNotPersistCommand() {
        UUID key = key();
        assertThatThrownBy(() -> controlsWriter.update(actorId, key, control("1", false, false), null, now))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007));
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key)).isEmpty();
    }

    private UUID key() { UUID key = UUID.randomUUID(); ownKeys.add(key); return key; }
    private RanchPolicyPublicationWriter.PublicationOutcome publish(UUID key, RanchPolicyPublicationRequest request) {
        var result = policiesWriter.publish(actorId, key, request, null, now);
        if (result.createdNow()) ownPolicies.add(result.response().id());
        return result;
    }
    private RanchPolicyPublicationRequest policy(String effective, boolean enabled, String cap) {
        var sources = Arrays.stream(RanchRewardSourceType.values()).map(type ->
                new RanchPolicyPublicationRequest.SourceRule(type, enabled, "1", 5)).toList();
        return new RanchPolicyPublicationRequest(Instant.parse(effective), enabled, cap, sources,
                new RanchPolicyPublicationRequest.Delivery(10, 30, 3, 1, 60), "POLICY_TEST");
    }
    private RanchOperationalControlsRequest control(String version, boolean deliveryPaused, boolean rewardsPaused) {
        return new RanchOperationalControlsRequest(version, false, false, deliveryPaused, rewardsPaused, "CONTROL_TEST");
    }
    private List<Result> pair(Supplier<RanchPolicyPublicationWriter.PublicationOutcome> left,
                              Supplier<RanchPolicyPublicationWriter.PublicationOutcome> right) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attempt(left); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attempt(right); });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("競合workerが終了していません");
        }
    }
    private Result attempt(Supplier<RanchPolicyPublicationWriter.PublicationOutcome> operation) {
        try { return new Result(operation.get(), null); }
        catch (BusinessException failure) { return new Result(null, failure); }
    }
    private record Result(RanchPolicyPublicationWriter.PublicationOutcome success, BusinessException failure) { }
}
