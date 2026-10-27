package com.mannschaft.app.ranch.reward;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公開不変policyの量・件数・週上限を混同しない純粋算定。 */
class RanchRewardCalculatorTest {
    private static RanchRewardPolicySnapshot policy() {
        var rules = new EnumMap<RanchRewardSourceType,
                RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        for (var source : RanchRewardSourceType.values()) {
            rules.put(source, new RanchRewardPolicySnapshot.SourceRule(true,
                    source == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE ? 25 : 10,
                    source == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE ? 4 : 10));
        }
        return new RanchRewardPolicySnapshot(UUID.randomUUID(), 1,
                Instant.parse("2026-10-05T00:00:00Z"), true, 100, rules,
                new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20),
                "PHASE1_TEST");
    }

    @Test
    void fourDifferentPersonalRecallFactsCanReachWholeWeekCapOnOneDay() {
        var policy = policy();
        long awarded = 0;
        for (int count = 0; count < 4; count++) {
            var result = RanchRewardCalculator.allocate(policy,
                    RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                    awarded, count);
            assertThat(result.status()).isEqualTo(RanchRewardDecisionStatus.AWARDED);
            assertThat(result.awardedPoints()).isEqualTo(25);
            awarded = Math.addExact(awarded, result.awardedPoints());
        }
        assertThat(awarded).isEqualTo(100);
        assertThat(RanchRewardCalculator.allocate(policy,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, awarded, 4)
                .status()).isEqualTo(RanchRewardDecisionStatus.SOURCE_COUNT_CAPPED);
    }

    @Test
    void partialGrantAndIndependentSourceCountAreDistinct() {
        var result = RanchRewardCalculator.allocate(policy(),
                RanchRewardSourceType.TIMELINE_ORIGINAL, 96, 0);
        assertThat(result.status()).isEqualTo(RanchRewardDecisionStatus.AWARDED);
        assertThat(result.requestedPoints()).isEqualTo(10);
        assertThat(result.awardedPoints()).isEqualTo(4);
        assertThat(RanchRewardCalculator.allocate(policy(),
                RanchRewardSourceType.TIMELINE_ORIGINAL, 100, 0)
                .status()).isEqualTo(RanchRewardDecisionStatus.CAPPED);
    }

    @Test
    void disabledSourceAndMalformedPolicyDoNotAllocate() {
        var base = policy();
        var rules = new EnumMap<>(base.sources());
        rules.put(RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                new RanchRewardPolicySnapshot.SourceRule(false, 10, 10));
        var disabled = new RanchRewardPolicySnapshot(base.policyId(), 1,
                base.effectiveAt(), true, 100, rules, base.delivery(), base.reasonCode());
        assertThat(RanchRewardCalculator.allocate(disabled,
                RanchRewardSourceType.BLOG_FIRST_PUBLISH, 0, 0)
                .status()).isEqualTo(RanchRewardDecisionStatus.SOURCE_DISABLED);
        assertThatThrownBy(() -> new RanchRewardPolicySnapshot(UUID.randomUUID(), 1,
                Instant.parse("2026-10-05T00:01:00Z"), true, 100, base.sources(),
                base.delivery(), base.reasonCode()))
                .isInstanceOf(IllegalArgumentException.class);
        var invalidRules = new EnumMap<>(base.sources());
        invalidRules.put(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                new RanchRewardPolicySnapshot.SourceRule(false, 25, 4));
        assertThatThrownBy(() -> new RanchRewardPolicySnapshot(UUID.randomUUID(), 1,
                base.effectiveAt(), true, 100, invalidRules,
                base.delivery(), base.reasonCode()))
                .isInstanceOf(IllegalArgumentException.class);
        var globallyDisabled = new RanchRewardPolicySnapshot(base.policyId(), 1,
                base.effectiveAt(), false, 100, invalidRules,
                base.delivery(), base.reasonCode());
        assertThat(RanchRewardCalculator.allocate(globallyDisabled,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, 0, 0))
                .extracting(RanchRewardCalculator.Allocation::status,
                        RanchRewardCalculator.Allocation::requestedPoints)
                .containsExactly(RanchRewardDecisionStatus.SOURCE_DISABLED, 0L);
    }
}
