package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

/** 最初の対象factで凍結した本人・UTC週の政策と残枠。 */
@Entity
@Table(name = "ranch_week_budgets")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchWeekBudgetEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;
    @Column(name = "week_starts_on", nullable = false)
    private LocalDate weekStartsOn;
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "json")
    private String ruleSnapshot;
    @Column(name = "global_cap", nullable = false)
    private long globalCap;
    @Column(name = "awarded_total", nullable = false)
    private long awardedTotal;
    @Column(name = "source_counts", nullable = false, columnDefinition = "json")
    private String sourceCounts;
    @Column(name = "version", nullable = false)
    private long version;

    public void record(long awardedPoints, String nextSourceCounts) {
        if (awardedPoints < 0 || awardedPoints > globalCap - awardedTotal
                || nextSourceCounts == null || nextSourceCounts.isBlank()) {
            throw new IllegalArgumentException("報酬週枠の更新値が不正です");
        }
        awardedTotal = Math.addExact(awardedTotal, awardedPoints);
        sourceCounts = nextSourceCounts;
        version = Math.addExact(version, 1);
    }
}
