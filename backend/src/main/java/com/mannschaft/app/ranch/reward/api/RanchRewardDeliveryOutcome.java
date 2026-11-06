package com.mannschaft.app.ranch.reward.api;

import java.util.Objects;
import java.util.UUID;

/** Ranch commit後に源へ返す最小terminal結果。本文や本人入力は含めない。 */
public record RanchRewardDeliveryOutcome(Outcome outcome, UUID decisionId, long awardedPoints) {
    public RanchRewardDeliveryOutcome {
        Objects.requireNonNull(outcome);
        if (awardedPoints < 0 || (outcome != Outcome.AWARDED && awardedPoints != 0)) {
            throw new IllegalArgumentException("配送結果の付与量が不正です");
        }
        if (outcome.isDecision() != (decisionId != null)) {
            throw new IllegalArgumentException("decision結果とIDが一致しません");
        }
        if (outcome == Outcome.AWARDED && awardedPoints == 0) {
            throw new IllegalArgumentException("AWARDEDには正の付与量が必要です");
        }
    }

    public boolean terminal() {
        return outcome != Outcome.DEFER;
    }

    public enum Outcome {
        AWARDED, CAPPED, SOURCE_COUNT_CAPPED, SOURCE_DISABLED,
        NOT_PARTICIPATING, REWARDS_PAUSED, INELIGIBLE,
        NOT_ENROLLED, ACCOUNT_DELETED, DEFER;

        public boolean isDecision() {
            return switch (this) {
                case AWARDED, CAPPED, SOURCE_COUNT_CAPPED, SOURCE_DISABLED,
                        NOT_PARTICIPATING, REWARDS_PAUSED, INELIGIBLE -> true;
                case NOT_ENROLLED, ACCOUNT_DELETED, DEFER -> false;
            };
        }
    }
}
