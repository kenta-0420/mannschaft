package com.mannschaft.app.organization;

/**
 * 加盟申請時のチームグループ選択モード（F01.2.1 §5.5）。
 *
 * <p>OFF=選択なし / OPTIONAL=任意 / REQUIRED=必須。{@code organizations.team_application_group_mode} の値と一致させる。</p>
 */
public enum TeamApplicationGroupMode {
    OFF,
    OPTIONAL,
    REQUIRED;

    /**
     * 保存値から実効値を求める（§5.5・§4.4）。
     *
     * <ul>
     *   <li>グループ機能が off なら、保存値にかかわらず {@link #OFF}（保存値は変えない）</li>
     *   <li>保存値が {@link #REQUIRED} で生存グループが0件なら {@link #OPTIONAL} に落とす（運用中に全グループを
     *       削除した場合。設定画面はこの差を見て警告を出す）</li>
     *   <li>それ以外は保存値そのもの</li>
     * </ul>
     *
     * @param stored          保存値（{@code organizations.team_application_group_mode}）
     * @param groupsEnabled   {@code organizations.team_groups_enabled}
     * @param liveGroupCount  組織の生存（未削除）チームグループ数
     * @return 実効モード
     */
    public static TeamApplicationGroupMode effective(
            TeamApplicationGroupMode stored, boolean groupsEnabled, long liveGroupCount) {
        if (!groupsEnabled || stored == null) {
            return OFF;
        }
        if (stored == REQUIRED && liveGroupCount <= 0) {
            return OPTIONAL;
        }
        return stored;
    }
}
