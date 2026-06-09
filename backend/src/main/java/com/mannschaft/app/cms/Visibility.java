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
    CUSTOM_TEMPLATE,
    /**
     * カスタム判定（F08.9 P4b ペイウォール連結）。
     *
     * <p>{@link com.mannschaft.app.common.visibility.StandardVisibility#CUSTOM} に写像され、
     * {@link com.mannschaft.app.cms.visibility.BlogPostVisibilityResolver#evaluateCustom}
     * 経由で {@link com.mannschaft.app.payment.service.PaymentGateService#checkAccess}
     * を呼ぶ。ペイウォール設定されたブログ記事に付与する。</p>
     */
    CUSTOM
}
