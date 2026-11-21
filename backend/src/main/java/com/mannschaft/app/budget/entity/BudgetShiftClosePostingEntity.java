package com.mannschaft.app.budget.entity;

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
 * 締めの投稿判定表（追記専用。CMP-260909-2152 確定設計 r12 §1.2・§3）。1イベントにつき投稿（POSTED）／中止（ABORTED）を
 * ちょうど1回だけ決める正本。UPDATE／DELETE の経路は無く、Entity に setter も持たない。
 *
 * <p>{@code close_event_id} は shiftbudget ドメインの ID のため FK を張らない（クロスドメイン FK 禁止）。</p>
 */
@Entity
@Table(name = "budget_shift_close_postings",
        uniqueConstraints = @UniqueConstraint(name = "uq_bscp_event", columnNames = "close_event_id"),
        indexes = @Index(name = "idx_bscp_allocation", columnList = "allocation_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BudgetShiftClosePostingEntity extends UuidV7Entity {

    /** 判定: 投稿。 */
    public static final String OUTCOME_POSTED = "POSTED";
    /** 判定: 中止。 */
    public static final String OUTCOME_ABORTED = "ABORTED";
    /** 中止の理由: 年度が CLOSED。 */
    public static final String REASON_FY_CLOSED = "FY_CLOSED";
    /** 中止の理由: 同割当同仕訳日の生存仕訳が既にある。 */
    public static final String REASON_JOURNAL_CONFLICT = "JOURNAL_CONFLICT";

    @Column(name = "close_event_id", nullable = false)
    private UUID closeEventId;

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    @Column(name = "fiscal_year_id", nullable = false)
    private Long fiscalYearId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "journal_transaction_id")
    private Long journalTransactionId;

    @Column(name = "reason", length = 40)
    private String reason;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @PrePersist
    void stampCreated() {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }
}
