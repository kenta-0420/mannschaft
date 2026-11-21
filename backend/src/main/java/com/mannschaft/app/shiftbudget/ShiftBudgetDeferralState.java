package com.mannschaft.app.shiftbudget;

/** 遅延記録（shift_budget_record_deferrals）の状態。 */
public enum ShiftBudgetDeferralState {
    /** 再実行待ち（シフト×締めイベントごとに1件）。 */
    PENDING,
    /** 再実行を完了した。 */
    REPLAYED,
    /** 再実行中に別の締めイベントが PREPARED だったため、新しい遅延行へ引き継いだ。 */
    REDEFERRED
}
