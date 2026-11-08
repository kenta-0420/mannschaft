package com.mannschaft.app.ranch.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 牧場内の公開済み規則の読み取り境界。運用規則がない本番では取得できない。 */
public interface RanchRuleProvider {
    boolean careEnabled();
    Optional<CareRuleSnapshot> currentCareRule(Instant now);
    Optional<EggRuleSnapshot> currentEggRule(Instant now);

    record CareRuleSnapshot(UUID ruleId, String ruleVersion, long amountXp,
                            long weeklyCapXp, long juvenileXp, long adultXp,
                            long affinityGain, long warmAffinity, long closeAffinity) { }

    record EggRuleSnapshot(String ruleVersion, long durationSeconds,
                          long smallCrackSeconds, long wideCrackSeconds,
                          long juvenileXp, long adultXp, long affinityGain,
                          long warmAffinity, long closeAffinity) { }
}
