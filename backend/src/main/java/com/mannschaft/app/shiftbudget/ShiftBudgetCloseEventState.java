package com.mannschaft.app.shiftbudget;

/**
 * 締めイベント（shift_budget_monthly_close_events）の状態（CMP-260909-2152 確定設計 r12 §1.1）。
 *
 * <ul>
 *   <li>{@link #PREPARED} — 段1 Prepare 済み。印付き PLANNED を抱える（段2 Post・段3 Commit 待ち）</li>
 *   <li>{@link #CLOSED} — 段3a Commit 済み（確定・仕訳済み）</li>
 *   <li>{@link #ABORTED} — 段3b Abort 済み。履歴として残す（物理削除しない）</li>
 * </ul>
 */
public enum ShiftBudgetCloseEventState {
    PREPARED,
    CLOSED,
    ABORTED
}
