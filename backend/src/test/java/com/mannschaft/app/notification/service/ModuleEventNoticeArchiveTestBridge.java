package com.mannschaft.app.notification.service;

/** L1-M 試練から実製品 archive INSERT の独立 TX を呼ぶ test-only 窓口。 */
public final class ModuleEventNoticeArchiveTestBridge {
    private ModuleEventNoticeArchiveTestBridge() { }

    /** 自己検体以外の移送対象0を呼出元で確認してから使う。 */
    public static int insertIntoArchive(NotificationCleanupBatchService cleanup) {
        return cleanup.insertIntoArchive();
    }
}
