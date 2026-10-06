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
        ownPolicies.forEach(policies::deleteById);
        if (controlTouched) {
            if (original == null) controls.deleteById(1); else controls.saveAndFlush(original);
        }
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
