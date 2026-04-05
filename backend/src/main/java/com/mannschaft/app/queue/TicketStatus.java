package com.mannschaft.app.queue;

/**
 * チケットステータス。順番待ちチケットのライフサイクル状態を表す。
 */
public enum TicketStatus {
    /** 待機中 */
    WAITING,
    /** 呼び出し済み */
    CALLED,
    /** 対応中 */
    SERVING,
    /** 完了 */
    COMPLETED,
    /** キャンセル済み */
    CANCELLED,
    /** 不在 */
    NO_SHOW
}
