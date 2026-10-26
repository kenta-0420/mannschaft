package com.mannschaft.app.property;

/**
 * 物件履歴パッケージの閲覧範囲。
 * F09.13 設計書 §5.5 マスキング処理表に対応。
 */
public enum WorkPackageVisibility {

    /** 管理者のみ閲覧可 */
    ADMINS_ONLY,

    /** メンバーまで閲覧可（金額含む全表示） */
    MEMBERS_ONLY,

    /** メンバーは金額マスクで閲覧可 */
    MEMBERS_MASKED,

    /** サポーターまで金額マスクで閲覧可 */
    PUBLIC_MASKED
}
