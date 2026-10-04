package com.mannschaft.app.ranch.reward;

import java.util.Objects;

/** 保存前の純粋な週報酬算定。参加/運営停止/再送は呼出側の境界で決定する。 */
public final class RanchRewardCalculator {
    private RanchRewardCalculator() { }

    public static Allocation allocate(RanchRewardPolicySnapshot policy,
                                      RanchRewardSourceType sourceType,
                                      long awardedTotal, long sourceCount) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(sourceType);
        if (awardedTotal < 0 || awardedTotal > policy.globalCap() || sourceCount < 0) {
            throw new IllegalArgumentException("保存済み週枠が不正です");
        }
        var rule = policy.sources().get(sourceType);
        if (rule == null) throw new IllegalArgumentException("報酬源がpolicyにありません");
        long requested = rule.amountPoints();
        if (!policy.enabled()) return new Allocation(RanchRewardDecisionStatus.SOURCE_DISABLED, 0, 0);
        if (!rule.enabled()) return new Allocation(RanchRewardDecisionStatus.SOURCE_DISABLED, requested, 0);
        if (sourceCount >= rule.countLimit()) {
            return new Allocation(RanchRewardDecisionStatus.SOURCE_COUNT_CAPPED, requested, 0);
        }
        long remaining = Math.subtractExact(policy.globalCap(), awardedTotal);
        if (remaining == 0) return new Allocation(RanchRewardDecisionStatus.CAPPED, requested, 0);
        return new Allocation(RanchRewardDecisionStatus.AWARDED, requested, Math.min(requested, remaining));
    }

    public record Allocation(RanchRewardDecisionStatus status, long requestedPoints, long awardedPoints) {
        public Allocation {
            Objects.requireNonNull(status);
            if (requestedPoints < 0 || awardedPoints < 0 || awardedPoints > requestedPoints) {
                throw new IllegalArgumentException("報酬算定値が不正です");
            }
        }
    }
}
