package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * 締め日の変更履歴（追記専用。UPDATE／DELETE の経路は無い。CMP-260909-2152 確定設計 r12 §1.1・§2）。
 */
@Entity
@Table(name = "shift_budget_closing_day_changes",
        uniqueConstraints = @UniqueConstraint(name = "uq_sbcdc_setting_revision", columnNames = {"setting_id", "revision"}),
        indexes = @Index(name = "idx_sbcdc_org_effective", columnList = "organization_id, effective_period_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetClosingDayChangeEntity extends UuidV7Entity {

    @Column(name = "setting_id", nullable = false)
    private UUID settingId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "revision", nullable = false)
    private int revision;

    /** 適用を開始する期間キー（YYYY-MM）。 */
    @Column(name = "effective_period_key", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String effectivePeriodKey;

    @Column(name = "closing_day", nullable = false)
    private int closingDay;

    @Column(name = "previous_closing_day", nullable = false)
    private int previousClosingDay;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void stampCreated() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
