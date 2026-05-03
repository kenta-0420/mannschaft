package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算消化記録エンティティ。
 *
 * <p>1 レコード = 1 (slot, user) ペア。シフト公開時に {@link ShiftBudgetConsumptionStatus#PLANNED}
 * で INSERT され、月次締めで {@link ShiftBudgetConsumptionStatus#CONFIRMED} へ昇格、
 * シフトキャンセル等で {@link ShiftBudgetConsumptionStatus#CANCELLED} へ遷移する。</p>
 *
 * <p>設計書 F08.7 (v1.2) §5.3 / §11 / §11.1 に準拠。</p>
 *
 * <p>運用ルール:</p>
 * <ul>
 *   <li>物理削除禁止。誤って書いた場合も {@code status = CANCELLED} に遷移させる（監査証跡）</li>
 *   <li>{@code status = CONFIRMED} 昇格後は {@code amount}/{@code hours}/{@code hourlyRateSnapshot}
 *       の UPDATE 不可（アプリ層で禁止）</li>
 * </ul>
 */
@Entity
@Table(
        name = "shift_budget_consumptions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_sbc_slot_user_status",
                        columnNames = {"slot_id", "user_id", "status", "deleted_at"}
                )
        },
        indexes = {
                @Index(name = "idx_sbc_allocation_status", columnList = "allocation_id, status"),
                @Index(name = "idx_sbc_slot", columnList = "slot_id"),
                @Index(name = "idx_sbc_user_recorded", columnList = "user_id, recorded_at"),
                @Index(name = "idx_sbc_shift", columnList = "shift_id, status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftBudgetConsumptionEntity extends BaseEntity {

    /** FK → shift_budget_allocations。ON DELETE RESTRICT */
    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /** FK → shift_schedules。ON DELETE RESTRICT */
    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    /** FK → shift_slots。ON DELETE RESTRICT */
    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    /** FK → users。ON DELETE RESTRICT */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 公開時点の適用時給（後の時給改定の影響を受けない） */
    @Column(name = "hourly_rate_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRateSnapshot;

    /** 勤務時間（深夜跨ぎは + 24h で計算済み） */
    @Column(name = "hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal hours;

    /** {@code hourlyRateSnapshot * hours} */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** ISO 4217 通貨コード。Phase 9 では JPY 固定 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** 消化ステータス */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShiftBudgetConsumptionStatus status;

    /** 公開イベント発火時刻 */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /** CONFIRMED 昇格時刻（月次締め時にセット） */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** CANCELLED 遷移時刻 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** キャンセル理由（CANCELLED 時のみ） */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 40)
    private ShiftBudgetCancelReason cancelReason;

    /** 論理削除タイムスタンプ（運用上は status 遷移を優先するが UNIQUE 制約用に保持） */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * PLANNED → CANCELLED 遷移を行う。
     * <p>{@code allocation.consumed_amount} の減算は呼出側で別途実施すること
     * （{@link com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository#decrementConsumedAmount}）。</p>
     */
    public void cancel(ShiftBudgetCancelReason reason) {
        this.status = ShiftBudgetConsumptionStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelReason = reason;
    }

    /**
     * PLANNED → CONFIRMED 昇格を行う（月次締め用）。
     */
    public void confirm() {
        this.status = ShiftBudgetConsumptionStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }
}
