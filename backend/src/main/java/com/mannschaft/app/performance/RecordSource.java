package com.mannschaft.app.performance;

/**
 * パフォーマンス記録のソース区分。
 */
public enum RecordSource {
    /** 管理者入力 */
    ADMIN,
    /** MEMBER自己入力 */
    SELF,
    /** スケジュール連動入力 */
    SCHEDULE,
    /** 活動記録から自動生成 */
    ACTIVITY
}
