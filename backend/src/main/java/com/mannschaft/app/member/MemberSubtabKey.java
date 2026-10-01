package com.mannschaft.app.member;

/**
 * CMP-260919-1140 Phase 1: メンバー統合画面のサブタブ種別。
 *
 * <p>組織ページの「メンバー」タブは「一覧（名簿）」「紹介」の2サブタブに統合される。
 * 各サブタブの閲覧可能範囲（最低必要ロール = {@link com.mannschaft.app.dashboard.MinRole}）は
 * 管理者がサブタブ単位で設定できる（項目単位の粒度は持たない）。</p>
 *
 * <p>DB永続層（{@code member_subtab_role_visibility.subtab_key}）は {@link #dbValue} の
 * lower_snake_case で保持する。</p>
 *
 * <p>設計書: docs/features/F06.6_member_subtab_visibility.md §3</p>
 */
public enum MemberSubtabKey {

    /** メンバー一覧（名簿）タブ。氏名・役割等を含むため PUBLIC 設定不可。 */
    MEMBER_LIST("member_list"),

    /** メンバー紹介タブ。連絡先を含まないため PUBLIC 設定可。 */
    MEMBER_PROFILES("member_profiles");

    private final String dbValue;

    MemberSubtabKey(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    /**
     * DB値（lower_snake_case）から MemberSubtabKey に変換する。
     *
     * @param value DB値
     * @return 対応する MemberSubtabKey
     * @throws IllegalArgumentException 未知の値の場合
     */
    public static MemberSubtabKey fromDbValue(String value) {
        for (MemberSubtabKey key : values()) {
            if (key.dbValue.equals(value)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown MemberSubtabKey db value: " + value);
    }
}
