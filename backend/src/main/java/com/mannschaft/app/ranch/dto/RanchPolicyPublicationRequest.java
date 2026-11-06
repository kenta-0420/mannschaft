package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.time.Instant;
import java.util.List;

/** 四源を一回ずつ含む全置換入力。保存snapshotのschemaはCORE正本を使用する。 */
public record RanchPolicyPublicationRequest(Instant effectiveAt, boolean enabled,
        String globalWeeklyCap, List<SourceRule> sources, Delivery delivery, String reasonCode) {
    public RanchPolicyPublicationRequest { sources = List.copyOf(sources); }
    public record SourceRule(RanchRewardSourceType sourceType, boolean enabled,
            String amountPoints, int countLimit) { }
    public record Delivery(int batchSize, int leaseSeconds, int maxAttempts,
            int initialBackoffSeconds, int maxBackoffSeconds) { }
}