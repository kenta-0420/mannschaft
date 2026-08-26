package com.mannschaft.app.reservation;

/**
 * キャンセル待ち（waitlist）エントリの状態（F03.4.5 §6.1）。
 *
 * <p>「期限切れ」は行として持たず、枠の開始時刻経過で導出しクリーンアップバッチが物理削除する
 * （{@code ReservationWaitlistCleanupBatchService}）。そのため本 enum は永続状態の 3 値のみ。</p>
 */
public enum WaitlistStatus {

    /** 待機中（キャンセルによる空き通知の対象）。 */
    WAITING,

    /** 本人取消。 */
    CANCELLED,

    /** 予約成立（同一 (slot, user) の予約作成成功で消し込み）。 */
    CONVERTED
}
