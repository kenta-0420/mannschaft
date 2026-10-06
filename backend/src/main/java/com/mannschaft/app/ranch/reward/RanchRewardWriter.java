package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.service.RanchDevelopmentFixturePolicyGate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.entity.RanchWeekBudgetEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** ACTIVE users lockの内側で、本人Ranchのdecision/週枠/残高/ledgerを一取引で確定する。 */
@Service
@RequiredArgsConstructor
public class RanchRewardWriter {
    private final RanchRewardDecisionRepository decisions;
    private final RanchOperationalControlRepository controls;
    private final RanchOwnerRepository owners;
    private final RanchParticipationPeriodRepository periods;
    private final RanchRewardPausePeriodRepository rewardPauses;
    private final RanchRewardPolicyRepository policies;
    private final RanchWeekBudgetRepository budgets;
    private final RanchPointLedgerRepository ledger;
    private final ObjectMapper json;
    private final Clock clock;
    private final RanchDevelopmentFixturePolicyGate development;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RanchRewardDeliveryOutcome decide(RanchRewardEnvelope event) {
        Long userId = event.recipientUserId();
        RanchRewardCanonicalKey key = RanchRewardCanonicalKey.of(event);
        var byEvent = decisions.findByEventId(event.eventId());
        if (byEvent.isPresent()) return replay(byEvent.orElseThrow(), event, key);
        var byKey = decisions.findByUserIdAndSourceTypeAndCanonicalKeyHash(
                userId, event.sourceType(), key.sha256());
        if (byKey.isPresent()) return replay(byKey.orElseThrow(), event, key);

    // 隔離DEVだけ同じ制御行ロックを使う。正式経路と保存済み再送の順序は維持する。
        boolean fixtureMode = development.enabled();
        var fixtureControl = fixtureMode ? controls.lockSingleton()
                .orElseThrow(() -> new IllegalStateException("牧場運用制御がありません")) : null;
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElse(null);
        if (owner == null || event.occurredAt().isBefore(owner.getCreatedAt())) {
            return outcome(RanchRewardDeliveryOutcome.Outcome.NOT_ENROLLED);
        }
        var control = fixtureControl != null ? fixtureControl : controls.findById(1).orElseThrow(() -> new IllegalStateException("牧場運営制御がありません"));
        if (control.isDeliveryPaused()) return outcome(RanchRewardDeliveryOutcome.Outcome.DEFER);

        Instant occurredAt = event.occurredAt();
        LocalDate week = occurredAt.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RanchRewardDecisionStatus zero = null;
        if (!periods.containsActiveAt(userId, occurredAt)) zero = RanchRewardDecisionStatus.NOT_PARTICIPATING;
        else if (rewardPauses.includes(occurredAt)) zero = RanchRewardDecisionStatus.REWARDS_PAUSED;
        else if (!RanchRewardQualification.eligible(event)) zero = RanchRewardDecisionStatus.INELIGIBLE;

        RanchWeekBudgetEntity budget = null;
        RanchRewardPolicySnapshot policy = null;
        boolean newBudget = false;
        if (zero == null) {
            budget = budgets.lockForWeek(userId, week).orElse(null);
            if (budget != null) {
                if (!owner.getId().equals(budget.getOwnerId())) throw new IllegalStateException("週枠の本人が一致しません");
                RanchRewardPolicyEntity saved = policies.findById(budget.getPolicyId())
                        .orElseThrow(() -> new IllegalStateException("凍結policyがありません"));
                policy = RanchRewardPolicyCodec.decode(saved.getId(), saved.getVersionNumber(),
                        saved.getEffectiveAt(), budget.getRuleSnapshot(), saved.getContentHash(), json);
                development.requireConsumptionAllowed(policy.reasonCode());
                if (!policy.enabled()) throw new IllegalStateException("無効policyに週枠があります");
                if (policy.globalCap() != budget.getGlobalCap()) throw new IllegalStateException("週枠の政策値が不一致です");
            } else {
                var candidate = fixtureMode
                        ? policies.publishedForDevelopment(occurredAt, PageRequest.of(0, 1))
                        : policies.publishedFor(occurredAt, PageRequest.of(0, 1));
                if (!candidate.isEmpty()) {
                    RanchRewardPolicyEntity selected = candidate.get(0);
                    policy = RanchRewardPolicyCodec.decode(selected.getId(), selected.getVersionNumber(),
                            selected.getEffectiveAt(), selected.getSettingsJson(), selected.getContentHash(), json);
                    development.requireConsumptionAllowed(policy.reasonCode());
                    if (policy.enabled()) {
                        String zeroCounts = countsJson(new EnumMap<>(RanchRewardSourceType.class));
                        budget = RanchWeekBudgetEntity.builder().ownerId(owner.getId()).userId(userId)
                                .weekStartsOn(week).policyId(policy.policyId())
                                .ruleSnapshot(RanchRewardPolicyCodec.encode(policy, json).json())
                                .globalCap(policy.globalCap()).awardedTotal(0).sourceCounts(zeroCounts)
                                .version(0).createdAt(now()).build();
                        newBudget = true;
                    }
                }
            }
            if (policy == null || !policy.enabled()) zero = RanchRewardDecisionStatus.SOURCE_DISABLED;
        }

        long requested = 0;
        long awarded = 0;
        RanchRewardDecisionStatus status = zero;
        if (status == null) {
            EnumMap<RanchRewardSourceType, Long> counts = counts(budget.getSourceCounts());
            var allocation = RanchRewardCalculator.allocate(policy, event.sourceType(),
                    budget.getAwardedTotal(), counts.get(event.sourceType()));
            status = allocation.status();
            requested = allocation.requestedPoints();
            awarded = allocation.awardedPoints();
            if (status == RanchRewardDecisionStatus.AWARDED || status == RanchRewardDecisionStatus.CAPPED) {
                counts.put(event.sourceType(), Math.addExact(counts.get(event.sourceType()), 1));
                budget.record(awarded, countsJson(counts));
                budgets.save(budget);
            } else if (newBudget) {
                budgets.save(budget);
            }
        }

        Instant decidedAt = now();
        UUID decisionId = UuidV7.generate();
        RanchRewardDecisionEntity decision = RanchRewardDecisionEntity.builder()
                .ownerId(owner.getId()).userId(userId).eventId(event.eventId())
                .sourceType(event.sourceType()).canonicalKeyHash(key.sha256())
                .canonicalKey(key.bytes()).rewardWeek(week)
                .policyId(policy == null ? null : policy.policyId()).status(status)
                .requestedPoints(requested).awardedPoints(awarded)
                .occurredAt(occurredAt).decidedAt(decidedAt).createdAt(decidedAt).build();
        decision.setId(decisionId);
        if (awarded > 0) {
            owner.credit(awarded);
            owners.save(owner);
            ledger.save(RanchPointLedgerEntity.builder().ownerId(owner.getId()).userId(userId)
                    .decisionId(decisionId).entryKind("REWARD").deltaPoints(awarded)
                    .balanceAfter(owner.getBalance()).deltaXp(0)
                    .ruleSnapshot(budget.getRuleSnapshot()).occurredAt(occurredAt)
                    .createdAt(decidedAt).build());
        }
        decisions.saveAndFlush(decision);
        return new RanchRewardDeliveryOutcome(
                RanchRewardDeliveryOutcome.Outcome.valueOf(status.name()), decisionId, awarded);
    }

    private RanchRewardDeliveryOutcome replay(RanchRewardDecisionEntity saved,
                                              RanchRewardEnvelope event, RanchRewardCanonicalKey key) {
        if (!saved.getUserId().equals(event.recipientUserId())
                || saved.getSourceType() != event.sourceType()
                || !key.sameStoredIdentity(saved.getCanonicalKey())
                || !Arrays.equals(key.sha256(), saved.getCanonicalKeyHash())) {
            throw new IllegalStateException("報酬正準keyまたはevent IDが衝突しました");
        }
        return new RanchRewardDeliveryOutcome(
                RanchRewardDeliveryOutcome.Outcome.valueOf(saved.getStatus().name()),
                saved.getId(), saved.getAwardedPoints());
    }

    private EnumMap<RanchRewardSourceType, Long> counts(String stored) {
        try {
            JsonNode node = json.readTree(stored);
            if (!node.isObject() || node.size() != RanchRewardSourceType.values().length) {
                throw new IllegalStateException("報酬件数snapshotが不正です");
            }
            EnumMap<RanchRewardSourceType, Long> result = new EnumMap<>(RanchRewardSourceType.class);
            for (var type : RanchRewardSourceType.values()) {
                JsonNode value = node.path(type.name());
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                    throw new IllegalStateException("報酬件数snapshotが不正です");
                }
                result.put(type, value.longValue());
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("報酬件数snapshotが不正です", exception);
        }
    }

    private String countsJson(Map<RanchRewardSourceType, Long> counts) {
        Map<String, Long> canonical = new TreeMap<>();
        for (var type : RanchRewardSourceType.values()) canonical.put(type.name(), counts.getOrDefault(type, 0L));
        try { return json.writeValueAsString(canonical); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("報酬件数を符号化できません", exception); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static RanchRewardDeliveryOutcome outcome(RanchRewardDeliveryOutcome.Outcome type) {
        return new RanchRewardDeliveryOutcome(type, null, 0);
    }
}
