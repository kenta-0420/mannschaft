package com.mannschaft.app.recruitment;

/**
 * F03.11 キャンセル料の決済ステータス。
 */
public enum CancellationPaymentStatus {
    /** 料金請求不要 (無料キャンセル) */
    NOT_REQUIRED,
    /** 決済待ち */
    PENDING,
    /** 決済完了 */
    PAID,
    /** 管理者免除 */
    WAIVED,
    /** 決済失敗 */
    FAILED
}
