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
 * </ul>
 */
public enum ShiftBudgetFailedEventType {
    CONSUMPTION_RECORD,
    CONSUMPTION_CANCEL,
    THRESHOLD_ALERT,
    WORKFLOW_START,
    NOTIFICATION_SEND
}
