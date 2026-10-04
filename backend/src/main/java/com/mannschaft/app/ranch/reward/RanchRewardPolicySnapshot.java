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
                                        Instant effectiveAt, boolean enabled, long globalCap,
                                        Map<RanchRewardSourceType, SourceRule> sources,
                                        DeliverySettings delivery, String reasonCode) {
    public RanchRewardPolicySnapshot {
        Objects.requireNonNull(policyId);
        Objects.requireNonNull(effectiveAt);
        Objects.requireNonNull(sources);
        Objects.requireNonNull(delivery);
        Objects.requireNonNull(reasonCode);
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
                || (enabled && !copied.get(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE).enabled())) {
            throw new IllegalArgumentException("無料本人想起を含む四源policyが必要です");
        }
        if (reasonCode.isBlank() || reasonCode.length() > 80
                || !reasonCode.equals(reasonCode.trim())) {
            throw new IllegalArgumentException("報酬policyの理由コードが不正です");
        }
        sources = Map.copyOf(copied);
    }

    public record DeliverySettings(int batchSize, int leaseSeconds, int maxAttempts,
                                   int initialBackoffSeconds, int maxBackoffSeconds) {
        public DeliverySettings {
            if (batchSize <= 0 || leaseSeconds <= 0 || maxAttempts <= 0
                    || initialBackoffSeconds <= 0 || maxBackoffSeconds <= 0
                    || initialBackoffSeconds > maxBackoffSeconds) {
                throw new IllegalArgumentException("報酬配送設定が不正です");
            }
        }
    }

    public record SourceRule(boolean enabled, long amountPoints, long countLimit) {
        public SourceRule {
            if (amountPoints <= 0 || countLimit <= 0 || countLimit > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("報酬量と件数上限は正である必要があります");
            }
        }
    }
}
