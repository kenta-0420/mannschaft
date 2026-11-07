package com.mannschaft.app.errorreport.service;

import org.springframework.stereotype.Component;

/**
 * CMP-261021-0021 の競合・ロールバック試験用の同期点。
 * 本番では何も行わず、試験では実 Bean を置き換えて実 DB の処理順だけを制御する。
 */
@Component
public class ErrorReportIngestHook {
    /** 既存の通常read直後。ロック取得やflush完了を意味しない。 */
    public void afterRead(String errorHash, boolean existing) { }

    /** 既存saveの戻り直後。IDENTITYのINSERT送信を観測できるがflush完了を意味しない。 */
    public void afterSave(String errorHash) { }

    /** 管理対象の通常read直後。hashロック取得を意味しない。 */
    public void afterManagementRead(String errorHash) { }

    /** locking read 完了後、行の更新前に呼ぶ。 */
    public void afterLockingRead(String errorHash, boolean existing) { }

    /** flush 成功後、プロセス内バッファへ追加する前に呼ぶ。 */
    public void afterFlush(String errorHash) { }

    /** 管理更新の全 hash 行ロック取得後、変更前に呼ぶ。 */
    public void afterManagementLock(String errorHash) { }
}
