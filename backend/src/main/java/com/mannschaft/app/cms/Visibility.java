package com.mannschaft.app.cms;

/**
 * コンテンツの公開範囲。
 */
public enum Visibility {
    PUBLIC,
    MEMBERS_ONLY,
    SUPPORTERS_AND_ABOVE,
    FOLLOWERS_ONLY,
    PRIVATE,
    /** カスタム公開範囲テンプレート参照（F01.7） */
    CUSTOM_TEMPLATE
}
