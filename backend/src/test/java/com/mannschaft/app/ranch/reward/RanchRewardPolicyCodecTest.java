package com.mannschaft.app.ranch.reward;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公開policyの四源値とcanonical hashを凍結する。 */
class RanchRewardPolicyCodecTest {
    private final ObjectMapper json = new ObjectMapper();

    private RanchRewardPolicySnapshot policy() {
        var rules = new EnumMap<RanchRewardSourceType,
                RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
            rules.put(type, new RanchRewardPolicySnapshot.SourceRule(true,
                    type == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE ? 25 : 10,
                    type == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE ? 4 : 5));
        }
        return new RanchRewardPolicySnapshot(UUID.randomUUID(), 7,
                Instant.parse("2026-10-05T00:00:00Z"), true, 100, rules,
                new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20),
                "PHASE1_TEST");
    }

    @Test
    void canonicalRoundTripPreservesAllSourceValues() {
        var policy = policy();
        var encoded = RanchRewardPolicyCodec.encode(policy, json);
        var parsed = RanchRewardPolicyCodec.decode(policy.policyId(), policy.versionNumber(),
                policy.effectiveAt(), encoded.json(), encoded.sha256(), json);
        assertThat(parsed).isEqualTo(policy);
        assertThat(RanchRewardPolicyCodec.encode(parsed, json).sha256())
                .containsExactly(encoded.sha256());
    }

    @Test
    void tamperedHashOrIncompleteSourceMapIsRejected() {
        var policy = policy();
        var encoded = RanchRewardPolicyCodec.encode(policy, json);
        byte[] wrong = encoded.sha256();
        wrong[0] ^= 1;
        assertThatThrownBy(() -> RanchRewardPolicyCodec.decode(policy.policyId(),
                policy.versionNumber(), policy.effectiveAt(), encoded.json(), wrong, json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RanchRewardPolicyCodec.decode(policy.policyId(),
                policy.versionNumber(), policy.effectiveAt(),
                "{\"globalCap\":100,\"sources\":{}}", encoded.sha256(), json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledPolicyRoundTripsWithoutTurningPersonalSourceOn() {
        var enabled = policy();
        var rules = new EnumMap<>(enabled.sources());
        rules.put(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                new RanchRewardPolicySnapshot.SourceRule(false, 25, 4));
        var disabled = new RanchRewardPolicySnapshot(enabled.policyId(), enabled.versionNumber(),
                enabled.effectiveAt(), false, enabled.globalCap(), rules,
                enabled.delivery(), enabled.reasonCode());
        var encoded = RanchRewardPolicyCodec.encode(disabled, json);
        var decoded = RanchRewardPolicyCodec.decode(disabled.policyId(), disabled.versionNumber(),
                disabled.effectiveAt(), encoded.json(), encoded.sha256(), json);
        assertThat(decoded.enabled()).isFalse();
        assertThat(decoded.sources().get(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE).enabled()).isFalse();
        assertThatThrownBy(() -> RanchRewardPolicyCodec.decode(disabled.policyId(),
                disabled.versionNumber(), disabled.effectiveAt(),
                "{\"globalCap\":100,\"sources\":{}}", encoded.sha256(), json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countLimitOutsidePublishedIntContractIsRejectedBeforeEncoding() {
        assertThatThrownBy(() -> new RanchRewardPolicySnapshot.SourceRule(true, 1,
                (long) Integer.MAX_VALUE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
