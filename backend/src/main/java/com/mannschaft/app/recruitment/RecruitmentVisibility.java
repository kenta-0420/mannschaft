package com.mannschaft.app.recruitment;

/**
 * F03.11 募集型予約の公開範囲。
 */
public enum RecruitmentVisibility {
    /** 全体公開 */
    PUBLIC,
    /** スコープ内のみ */
    SCOPE_ONLY,
    /** サポーターのみ */
    SUPPORTERS_ONLY,
    /** カスタム公開範囲テンプレート参照（F01.7） */
    CUSTOM_TEMPLATE,
    /**
     * 個人札で作成時に固定した所属チーム・組織だけへ公開する。
     * 公開判定は mutable な VisibilityTemplate ではなく listing 専用の
     * recruitment_listing_audience_scopes を用いる。
     */
    SELECTED_SCOPES,
    /**
     * フレンドチーム限定の非公開札（F22.1 市）。
     * 公開市には並ばず、{@code recruitment_friend_targets} で解決された
     * フレンドチームのメンバーのみが閲覧・応募できる。第三者には 404 で存在秘匿する。
     * DDL 不要（{@code visibility} は VARCHAR(20)・Java enum 管理）。
     */
    FRIEND_TEAMS_ONLY
}
