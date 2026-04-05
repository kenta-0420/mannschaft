package com.mannschaft.app.moderation;

/**
 * 違反種別。
 */
public enum ViolationType {
    /** 警告 */
    WARNING,
    /** コンテンツ削除 */
    CONTENT_DELETE,
    /** 期限付き凍結 */
    TEMPORARY_FREEZE,
    /** 無期限凍結 */
    USER_FREEZE
}
