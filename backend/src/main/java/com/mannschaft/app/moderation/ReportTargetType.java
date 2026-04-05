package com.mannschaft.app.moderation;

/**
 * 通報対象の種別。
 */
public enum ReportTargetType {
    /** タイムライン投稿 */
    TIMELINE_POST,
    /** タイムラインコメント（返信） */
    TIMELINE_COMMENT,
    /** ソーシャルプロフィール */
    SOCIAL_PROFILE,
    /** ユーザー */
    USER
}
