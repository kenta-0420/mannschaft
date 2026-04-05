package com.mannschaft.app.schedule;

/**
 * スケジュールの最小閲覧ロール。
 */
public enum MinViewRole {
    /** 誰でも */
    ANYONE,
    /** SUPPORTER以上 */
    SUPPORTER_PLUS,
    /** MEMBER以上 */
    MEMBER_PLUS,
    /** ADMIN限定 */
    ADMIN_ONLY
}
