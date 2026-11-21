package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseActorKind;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventOrigin;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * 割当×締め期間の締めイベント（(c)。3段締めの状態を持つ。CMP-260909-2152 確定設計 r12 §1.1・§3）。
 *
 * <p>{@code target_month} の意味は「締め期間キー」（列名は維持）。{@code active_month}・{@code active_journal_date} は
 * ABORTED を除いた一意性のための生成列。ABORTED は履歴として残す（物理削除しない）。</p>
 */
@Entity
@Table(name = "shift_budget_monthly_close_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_sbmce_allocation_active_month",
                        columnNames = {"allocation_id", "active_month"}),
                @UniqueConstraint(name = "uq_sbmce_allocation_active_journal",
                        columnNames = {"allocation_id", "active_journal_date"})
        },
        indexes = @Index(name = "idx_sbmce_allocation_period", columnList = "allocation_id, close_period_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetMonthlyCloseEventEntity extends UuidV7Entity {

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /** 締め期間キー（YYYY-MM）。 */
    @Column(name = "target_month", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String targetMonth;

    @Column(name = "close_period_id", nullable = false)
    private UUID closePeriodId;

    @Column(name = "journal_date", nullable = false)
    private LocalDate journalDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ShiftBudgetCloseEventState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private ShiftBudgetCloseEventOrigin origin;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "planned_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal plannedTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16)
    private ShiftBudgetCloseActorKind actorKind;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "prepared_at", nullable = false)
    private Instant preparedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "aborted_at")
    private Instant abortedAt;

    /** FK なし（クロスドメイン）。CLOSED のみ非 NULL。 */
    @Column(name = "journal_transaction_id")
    private Long journalTransactionId;

    @GeneratedColumn("CASE WHEN state <> 'ABORTED' THEN target_month END")
    @Column(name = "active_month", length = 7, insertable = false, updatable = false)
    private String activeMonth;

    @GeneratedColumn("CASE WHEN state <> 'ABORTED' THEN journal_date END")
    @Column(name = "active_journal_date", insertable = false, updatable = false)
    private LocalDate activeJournalDate;

    /** Commit（判定 POSTED）。 */
    public void markClosed(Long journalTransactionId) {
        this.state = ShiftBudgetCloseEventState.CLOSED;
        this.journalTransactionId = journalTransactionId;
        this.closedAt = Instant.now();
    }

    /** Abort（判定 ABORTED）。 */
    public void markAborted() {
        this.state = ShiftBudgetCloseEventState.ABORTED;
        this.abortedAt = Instant.now();
    }
}
