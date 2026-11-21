package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.shiftbudget.ShiftBudgetDeferralState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GeneratedColumn;

import java.time.Instant;
import java.util.UUID;

/**
 * 遅延記録（シフト単位。CMP-260909-2152 確定設計 r12 §4.4）。確保・延長・確定は DB の NOW() と所有者で判定し、
 * 更新は Repository の UPDATE 文で行う（Entity は INSERT 専用）。
 *
 * <p>{@code pending_key} は PENDING の間だけ締めイベント ID を持つ生成列で、シフト×イベントごとに PENDING は1件になる。</p>
 */
@Entity
@Table(name = "shift_budget_record_deferrals",
        uniqueConstraints = @UniqueConstraint(name = "uq_sbrd_shift_pending", columnNames = {"shift_id", "pending_key"}),
        indexes = {
                @Index(name = "idx_sbrd_event_state", columnList = "close_event_id, state"),
                @Index(name = "idx_sbrd_state_created", columnList = "state, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetRecordDeferralEntity extends UuidV7Entity {

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "close_event_id", nullable = false)
    private UUID closeEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ShiftBudgetDeferralState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claimed_by", length = 36)
    private String claimedBy;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @GeneratedColumn("CASE WHEN state = 'PENDING' THEN close_event_id END")
    @Column(name = "pending_key", insertable = false, updatable = false)
    private UUID pendingKey;

    @PrePersist
    void stampCreated() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
