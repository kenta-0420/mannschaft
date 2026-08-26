package com.mannschaft.app.cms;

/**
 * コンテンツの公開範囲。
 *
 * <p>可視性ラダー統一（#1341）で {@link com.mannschaft.app.common.visibility.StandardVisibility}
 * は旧 {@code MEMBERS_ONLY/ADMINS_ONLY} を新ラダー
 * （{@code PUBLIC > SUPPORTERS_AND_ABOVE > MEMBERS_AND_ABOVE > ADMINS_AND_ABOVE}）＋直接所属軸
 * {@code SCOPE_AFFILIATED} に統一した。FE のブログ可視性 UI は正準ラダーの値名
 * （例: {@code MEMBERS_AND_ABOVE}）をそのまま送るため、tournament ドメイン
 * （{@link com.mannschaft.app.tournament.TournamentVisibility}）に倣って cms enum へも
 * 新ラダー値名を追加し、{@code Visibility.valueOf(...)} で受理できるようにする。
 * StandardVisibility への写像は
 * {@link com.mannschaft.app.common.visibility.mapping.CmsVisibilityMapper} に一元化する。</p>
 *
 * <p>旧 {@link #MEMBERS_ONLY} は既存データ・既存呼び出しとの互換のため残置し、
 * Mapper で {@code StandardVisibility.MEMBERS_AND_ABOVE} へ写像する（新 {@link #MEMBERS_AND_ABOVE}
 * と同一の可視範囲）。</p>
 */
public enum Visibility {
    PUBLIC,
    MEMBERS_ONLY,
    SUPPORTERS_AND_ABOVE,
    /** メンバー以上が閲覧可能（応援者除外・新ラダー）。{@code StandardVisibility.MEMBERS_AND_ABOVE}。 */
    MEMBERS_AND_ABOVE,
    /** 管理者以上のみ閲覧可能（新ラダー）。{@code StandardVisibility.ADMINS_AND_ABOVE}。 */
    ADMINS_AND_ABOVE,
    /**
     * スコープへの直接所属者のみ閲覧可能（応援者・ゲスト含む直接所属軸・旧 MEMBERS_ONLY 相当の正準値）。
     * {@code StandardVisibility.SCOPE_AFFILIATED}。
     */
    SCOPE_AFFILIATED,
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
