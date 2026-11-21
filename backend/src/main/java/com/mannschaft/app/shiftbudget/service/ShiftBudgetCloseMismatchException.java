package com.mannschaft.app.shiftbudget.service;

/**
 * 段3a Commit の件数・合計が Prepare 時と一致しない（CMP-260909-2152 決定5）。Commit TX はロールバックされ、イベントは PREPARED・
 * 判定は POSTED のまま残る。段取り役が失敗イベント COMMIT_MISMATCH（自動再試行の対象外）を記録し、手作業で復旧してから Commit を再実行する。
 */
public class ShiftBudgetCloseMismatchException extends RuntimeException {

    public ShiftBudgetCloseMismatchException(String message) {
        super(message);
    }
}
