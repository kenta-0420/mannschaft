package com.mannschaft.app.skill;

/**
 * スキル・資格のステータス。
 */
public enum SkillStatus {

    /** 有効（認証済み・期限内） */
    ACTIVE,

    /** 期限切れ */
    EXPIRED,

    /** レビュー待ち */
    PENDING_REVIEW
}
