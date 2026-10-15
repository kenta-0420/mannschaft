package com.mannschaft.app.recruitment;

/**
 * F03.11 キャンセル発生の起点種別。
 * recruitment_cancellation_records.cancel_source に格納される。
 */
public enum CancellationSource {
    /** ユーザー本人によるキャンセル */
    USER,
    /** 管理者によるキャンセル */
    ADMIN,
    /** 最小定員未達による自動キャンセル (Phase 3) */
    SYSTEM_AUTO_CANCEL,
    /** 無断欠席判定 (Phase 5b) */
    SYSTEM_NO_SHOW
}
