package com.mannschaft.app.shiftbudget;

/**
 * F08.7 Phase 10-β: 通知失敗 / hook 失敗イベントの種別。
 *
 * <p>設計書 §13 Phase 10-β に列挙された 5 種類。
 * DB 側でも {@code chk_sbfe_event_type} CHECK 制約で同一の文字列で固定する。</p>
 *
 * <ul>
 *   <li>{@link #CONSUMPTION_RECORD} — シフト公開時の消化記録 hook 失敗</li>
 *   <li>{@link #CONSUMPTION_CANCEL} — シフトアーカイブ時の消化キャンセル hook 失敗</li>
 *   <li>{@link #THRESHOLD_ALERT}    — 閾値判定 / 警告レコード INSERT 失敗</li>
 *   <li>{@link #WORKFLOW_START}     — 100% 到達時の F05.6 ワークフロー起動失敗</li>
 *   <li>{@link #NOTIFICATION_SEND}  — プッシュ / メール / アプリ内通知の送信失敗</li>
 *   <li>{@link #MONTHLY_CLOSE}      — 期間締めの失敗（CMP-260909-2152）</li>
 * </ul>
 */
public enum ShiftBudgetFailedEventType {
    CONSUMPTION_RECORD,
    CONSUMPTION_CANCEL,
    THRESHOLD_ALERT,
    WORKFLOW_START,
    NOTIFICATION_SEND,
    /**
     * 月次（期間）締めの失敗（CMP-260909-2152）。payload.operation に POST_FY_CLOSED／JOURNAL_CONFLICT／COMMIT_MISMATCH／
     * CANCEL_AFTER_CLOSE／DEFERRAL_REPLAY_FAILED／CLOSE_FAILED などを持つ。自動再試行の対象外（手作業・手動の再締めで回収）。
     */
    MONTHLY_CLOSE;

    /** 再実行アダプタが存在する種別だけを許可する。追加種別は明示対応まで許可しない（MONTHLY_CLOSE・CONSUMPTION_* は手動補正運用で対象外）。 */
    public boolean supportsRetry() {
        return this == THRESHOLD_ALERT || this == WORKFLOW_START || this == NOTIFICATION_SEND;
    }
}
