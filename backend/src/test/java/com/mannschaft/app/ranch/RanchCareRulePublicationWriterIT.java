package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.service.RanchCareRulePublicationWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLでcare版と固定ACKの同一TXを検証する。HTTPのfresh管理者認可とは別の証明。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchCareRulePublicationWriterIT extends AbstractMySqlIntegrationTest {
    @Autowired RanchCareRulePublicationWriter writer;
    @Autowired RanchCareRuleRepository rules;
    @Autowired RanchAdminCommandRepository commands;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired UserRepository users;
    private Long actorId;
    private final List<UUID> ownKeys = new CopyOnWriteArrayList<>();
    private final List<UUID> ownRules = new CopyOnWriteArrayList<>();
    private final Instant now = Instant.parse("2026-10-05T00:00:00.123456Z");

    @BeforeEach
    void syntheticActorAndMigrationEquivalentSingleton() {
        actorId = users.saveAndFlush(RanchTestFixture.user()).getId();
        // test profileはFlyway無効。seedの再現はfixture内だけで行い、production GETには置かない。
        if (controls.findById(1).isEmpty()) {
            controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false)
                    .shopEnabled(false).deliveryPaused(true).version(0).createdAt(now).updatedAt(now).build());
        }
    }

    @AfterEach
    void removeOnlyThisTestCommandsAndPublishedRules() {
        for (UUID key : ownKeys) {
            commands.findByActorUserIdAndIdempotencyKey(actorId, key).ifPresent(command -> commands.deleteById(command.getId()));
        }
        for (UUID id : ownRules) rules.deleteById(id);
        ownKeys.clear();
        ownRules.clear();
    }

    @Test
    void replayPreservesAckAfterEffectiveTimeAndRejectsDifferentBody() {
        UUID key = ownKey();
        var request = request("2031-01-06T00:00:00Z", "20");
        var first = publish(key, request);
        var replay = writer.publish(actorId, key, request, Instant.parse("2031-01-07T00:00:00Z"));
        assertThat(first.createdNow()).isTrue();
        assertThat(replay.createdNow()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(rules.findById(first.response().id()).orElseThrow().getContentHash()).hasSize(32);
        assertThatThrownBy(() -> writer.publish(actorId, key, request("2031-01-06T00:00:00Z", "21"), now))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key).orElseThrow().getCompletedAt()).isEqualTo(now);
        assertThat(rules.findById(first.response().id()).orElseThrow().getAmountXp()).isEqualTo(20L);
    }

    @Test
    void rejectedPublicationDoesNotSaveCommand() {
        UUID key = ownKey();
        assertThatThrownBy(() -> writer.publish(actorId, key, request("2026-10-05T00:00:00Z", "20"), now))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key)).isEmpty();
    }

    @Test
    void concurrentSameKeyReturnsOneCreationAndTheIdenticalSavedAck() throws Exception {
        UUID key = ownKey();
        var request = request("2031-01-13T00:00:00Z", "20");
        var results = pair(() -> publish(key, request), () -> publish(key, request));
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.stream().filter(result -> result.success().createdNow())).hasSize(1);
        assertThat(results.get(0).success().response()).isEqualTo(results.get(1).success().response());
    }

    @Test
    void concurrentDifferentKeysCannotPublishTwoRulesAtTheSameEffectiveTime() throws Exception {
        UUID first = ownKey();
        UUID second = ownKey();
        var request = request("2031-01-20T00:00:00Z", "20");
        var results = pair(() -> publish(first, request), () -> publish(second, request));
        assertThat(results.stream().filter(result -> result.success() != null)).hasSize(1);
        assertThat(results.stream().filter(result -> result.failure() != null))
                .singleElement().satisfies(result -> assertThat(result.failure().getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007));
        assertThat(List.of(first, second).stream().filter(key -> commands.findByActorUserIdAndIdempotencyKey(actorId, key).isPresent())).hasSize(1);
    }

    @Test
    void concurrentDifferentEffectiveTimesReceiveDistinctConsecutiveVersions() throws Exception {
        UUID first = ownKey();
        UUID second = ownKey();
        var results = pair(() -> publish(first, request("2031-01-27T00:00:00Z", "20")),
                () -> publish(second, request("2031-02-03T00:00:00Z", "20")));
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        var versions = results.stream().map(result -> Long.parseLong(result.success().response().version())).sorted().toList();
        assertThat(versions.get(1)).isEqualTo(versions.get(0) + 1);
        assertThat(results.get(0).success().response().id()).isNotEqualTo(results.get(1).success().response().id());
    }

    private UUID ownKey() {
        UUID key = UUID.randomUUID();
        ownKeys.add(key);
        return key;
    }

    private RanchCareRulePublicationWriter.PublicationOutcome publish(UUID key, RanchCareRulePublicationRequest request) {
        var result = writer.publish(actorId, key, request, now);
        if (result.createdNow()) ownRules.add(result.response().id());
        return result;
    }

    private List<Result> pair(Supplier<RanchCareRulePublicationWriter.PublicationOutcome> first,
                              Supplier<RanchCareRulePublicationWriter.PublicationOutcome> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var left = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attempt(first); });
            var right = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attempt(second); });
            return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("競合試験workerが終了していません");
        }
    }

    private Result attempt(Supplier<RanchCareRulePublicationWriter.PublicationOutcome> operation) {
        try { return new Result(operation.get(), null); }
        catch (BusinessException failure) { return new Result(null, failure); }
    }

    private record Result(RanchCareRulePublicationWriter.PublicationOutcome success, BusinessException failure) { }

    private RanchCareRulePublicationRequest request(String at, String amount) {
        return new RanchCareRulePublicationRequest(Instant.parse(at), amount, "100", "60", "100", "CARE_RULE_TEST");
    }
}
