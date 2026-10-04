package com.mannschaft.app.ranch.reward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.dto.WeekBudget;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.entity.RanchWeekBudgetEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/** Auth の users lock 下で順次呼ぶ、牧場行だけの PRIMARY 表示投影。 */
@Service
@RequiredArgsConstructor
public class RanchRewardProjectionReader {
    private final RanchOperationalControlRepository controls;
    private final RanchRewardPausePeriodRepository pauses;
    private final RanchRewardPolicyRepository policies;
    private final RanchWeekBudgetRepository budgets;
    private final RanchOwnerRepository owners;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Projection current(Long userId, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(serverTime);
        var control = controls.findById(1)
                .orElseThrow(() -> new IllegalStateException("牧場運営制御がありません"));
        LocalDate week = serverTime.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant weekEnd = week.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        var latest = policies.publishedFor(serverTime, PageRequest.of(0, 1));
        RanchRewardPolicyEntity published = latest.isEmpty() ? null : latest.get(0);
        RanchRewardPolicySnapshot currentPolicy = published == null ? null : decode(published,
                published.getSettingsJson());
        String status = currentPolicy == null || !currentPolicy.enabled() ? "DISABLED"
                : pauses.includes(serverTime) ? "PAUSED" : "ENABLED";
        String version = published == null ? null : Long.toString(published.getVersionNumber());

        WeekBudget currentWeek = null;
        if (owners.findByUserId(userId).isPresent()) {
            RanchWeekBudgetEntity saved = budgets.findByUserIdAndWeekStartsOn(userId, week).orElse(null);
            if (saved != null) {
                RanchRewardPolicyEntity frozen = policies.findById(saved.getPolicyId())
                        .orElseThrow(() -> new IllegalStateException("凍結policyがありません"));
                RanchRewardPolicySnapshot rule = decode(frozen, saved.getRuleSnapshot());
                if (!rule.enabled() || rule.globalCap() != saved.getGlobalCap()
                        || saved.getAwardedTotal() < 0 || saved.getAwardedTotal() > saved.getGlobalCap()) {
                    throw new IllegalStateException("報酬週枠が不正です");
                }
                long completed = personalCount(saved.getSourceCounts());
                currentWeek = weekBudget(week, weekEnd, rule, saved.getAwardedTotal(), completed);
                version = Long.toString(rule.versionNumber());
            } else if (currentPolicy != null && currentPolicy.enabled()) {
                currentWeek = weekBudget(week, weekEnd, currentPolicy, 0, 0);
            }
        }
        return new Projection(control.isDeliveryPaused(), status, currentWeek, version);
    }

    private RanchRewardPolicySnapshot decode(RanchRewardPolicyEntity entity, String body) {
        return RanchRewardPolicyCodec.decode(entity.getId(), entity.getVersionNumber(),
                entity.getEffectiveAt(), body, entity.getContentHash(), json);
    }

    private long personalCount(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode value = root.path(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE.name());
            if (!root.isObject() || root.size() != RanchRewardSourceType.values().length
                    || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
                throw new IllegalStateException("報酬件数snapshotが不正です");
            }
            return value.longValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("報酬件数snapshotが不正です", exception);
        }
    }

    private WeekBudget weekBudget(LocalDate week, Instant weekEnd,
                                  RanchRewardPolicySnapshot policy, long awarded, long personalCount) {
        long remaining = Math.subtractExact(policy.globalCap(), awarded);
        long amount = policy.sources().get(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE).amountPoints();
        long required = remaining / amount + (remaining % amount == 0 ? 0 : 1);
        return new WeekBudget(week, weekEnd, Long.toString(policy.globalCap()),
                Long.toString(awarded), Long.toString(remaining),
                Long.toString(policy.versionNumber()), Long.toString(required),
                Math.toIntExact(personalCount));
    }

    public record Projection(boolean deliveryPaused, String rewardsStatus,
                             WeekBudget weekBudget, String policyVersion) { }
}
