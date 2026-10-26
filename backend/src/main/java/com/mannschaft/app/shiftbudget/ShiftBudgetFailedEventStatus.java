package com.mannschaft.app.shiftbudget;

/**
 * F08.7 Phase 10-β: 通知失敗 / hook 失敗イベントのステータス。
 *
 * <p>遷移:</p>
 * <ul>
 *   <li>{@link #PENDING}        — 初回記録時の状態（リトライ未着手）</li>
 *   <li>{@link #RETRYING}       — リトライバッチが処理を試行中</li>
 *   <li>{@link #SUCCEEDED}      — リトライで再実行が成功</li>
 *   <li>{@link #EXHAUSTED}      — {@code retry_count} が上限（3 回）に達して諦めた</li>
 *   <li>{@link #MANUAL_RESOLVED} — 運用者が管理 API で「手動補正済」マーク</li>
 * </ul>
 *
 * <p>EXHAUSTED / MANUAL_RESOLVED は終端ステータスでバッチは再実行しない。
 * 個別再実行 API（{@code POST /failed-events/{id}/retry}）は EXHAUSTED に対しても
 * BUDGET_ADMIN 権限で起動可能（運用判断による再試行を許容）。</p>
 */
public enum ShiftBudgetFailedEventStatus {
    PENDING,
    RETRYING,
    SUCCEEDED,
    EXHAUSTED,
    MANUAL_RESOLVED
}
