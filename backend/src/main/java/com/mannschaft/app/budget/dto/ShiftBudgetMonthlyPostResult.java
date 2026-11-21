package com.mannschaft.app.budget.dto;

/**
 * 3段締めの段2 Post の判定（budget_shift_close_postings の保存済み判定。CMP-260909-2152 確定設計 r12 §3）。
 *
 * @param outcome              POSTED／ABORTED
 * @param journalTransactionId POSTED の仕訳 ID（ABORTED は null）
 * @param reason               ABORTED の理由（FY_CLOSED／JOURNAL_CONFLICT）
 */
public record ShiftBudgetMonthlyPostResult(Outcome outcome, Long journalTransactionId, String reason) {

    /** 判定。 */
    public enum Outcome {
        POSTED,
        ABORTED
    }
}
