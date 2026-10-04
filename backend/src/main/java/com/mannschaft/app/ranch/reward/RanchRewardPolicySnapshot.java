package com.mannschaft.app.ranch.reward;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 公開済み不変policyから週行へ複製する最小の報酬計算値。 */
public record RanchRewardPolicySnapshot(UUID policyId, long versionNumber,
                                        Instant effectiveAt, long globalCap,
                                        Map<RanchRewardSourceType, SourceRule> sources) {
    public RanchRewardPolicySnapshot {
        Objects.requireNonNull(policyId);
        Objects.requireNonNull(effectiveAt);
        Objects.requireNonNull(sources);
        if (versionNumber <= 0 || globalCap <= 0
                || effectiveAt.atZone(ZoneOffset.UTC).getDayOfWeek() != DayOfWeek.MONDAY
                || !effectiveAt.atZone(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("報酬policyの版、上限、有効UTC週境界が不正です");
        }
        EnumMap<RanchRewardSourceType, SourceRule> copied = new EnumMap<>(RanchRewardSourceType.class);
        copied.putAll(sources);
        if (copied.size() != RanchRewardSourceType.values().length
                || !copied.keySet().containsAll(java.util.Set.of(RanchRewardSourceType.values()))
                || copied.values().stream().anyMatch(Objects::isNull)
                || !copied.get(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE).enabled()) {
            throw new IllegalArgumentException("無料本人想起を含む四源policyが必要です");
        }
        sources = Map.copyOf(copied);
    }

    public record SourceRule(boolean enabled, long amountPoints, long countLimit) {
        public SourceRule {
            if (amountPoints <= 0 || countLimit <= 0) {
                throw new IllegalArgumentException("報酬量と件数上限は正である必要があります");
            }
        }
    }
}
