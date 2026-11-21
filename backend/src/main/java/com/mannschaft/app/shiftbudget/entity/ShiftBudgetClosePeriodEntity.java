package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.Instant;

/**
 * 組織の締め期間の確定行（初回の締めで境界を固定する。期間キー＝締め日が属する月。CMP-260909-2152 確定設計 r12 §1.1・§2）。
 */
@Entity
@Table(name = "shift_budget_close_periods",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_sbcp_org_key", columnNames = {"organization_id", "period_key"}),
                @UniqueConstraint(name = "uq_sbcp_org_start", columnNames = {"organization_id", "period_start"}),
                @UniqueConstraint(name = "uq_sbcp_org_end", columnNames = {"organization_id", "period_end"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetClosePeriodEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "period_key", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String periodKey;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "closing_day", nullable = false)
    private int closingDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void stampCreated() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
