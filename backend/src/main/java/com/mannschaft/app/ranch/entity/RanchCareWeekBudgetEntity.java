package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_care_week_budgetsの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_care_week_budgets")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchCareWeekBudgetEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "week_starts_on", nullable = false)
    private LocalDate weekStartsOn;
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "json")
    private String ruleSnapshot;
    @Column(name = "weekly_cap_xp", nullable = false)
    private long weeklyCapXp;
    @Column(name = "awarded_xp", nullable = false)
    private long awardedXp;
    @Column(name = "version", nullable = false)
    private long version;

    public void award(long gainedXp) {
        if (gainedXp < 0 || gainedXp > weeklyCapXp - awardedXp) {
            throw new IllegalArgumentException("週の無料care枠を超えています");
        }
        awardedXp = Math.addExact(awardedXp, gainedXp);
        version = Math.addExact(version, 1);
    }
}
