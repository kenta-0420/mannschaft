package com.mannschaft.app.knowledgebase;

/**
 * ナレッジベースページのアクセスレベル。
 */
public enum PageAccessLevel {

    /** 全メンバーが閲覧可能 */
    ALL_MEMBERS,

    /** 管理者のみ閲覧可能 */
    ADMIN_ONLY,

    /** カスタム権限設定 */
    CUSTOM
}
