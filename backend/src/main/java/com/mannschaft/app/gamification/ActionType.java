package com.mannschaft.app.gamification;

/**
 * ポイント付与アクション種別。
 */
public enum ActionType {

    /** タイムライン投稿 */
    TIMELINE_POST,

    /** 活動参加 */
    ACTIVITY_PARTICIPATE,

    /** スケジュール出席 */
    SCHEDULE_ATTEND,

    /** ナレッジベース作成 */
    KNOWLEDGE_BASE_CREATE,

    /** デイリーログイン */
    DAILY_LOGIN,

    /** スキル登録 */
    SKILL_REGISTER,

    /** 管理者調整 */
    ADMIN_ADJUST
}
