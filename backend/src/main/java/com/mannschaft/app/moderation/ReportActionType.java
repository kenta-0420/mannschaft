package com.mannschaft.app.moderation;

/**
 * 通報対応アクションの種別。
 */
public enum ReportActionType {
    /** 警告 */
    WARNING,
    /** コンテンツ削除 */
    CONTENT_DELETE,
    /** ユーザー凍結 */
    USER_FREEZE,
    /** 却下 */
    DISMISS,
    /** エスカレーション */
    ESCALATE,
    /** 差し戻し */
    REOPEN,
    /** 一時凍結 */
    TEMPORARY_FREEZE
}
