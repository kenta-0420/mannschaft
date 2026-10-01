package com.mannschaft.app.organization;

/**
 * 加盟申請時のチームグループ選択モード（F01.2.1 §5.5）。
 *
 * <p>OFF=選択なし / OPTIONAL=任意 / REQUIRED=必須。{@code organizations.team_application_group_mode} の値と一致させる。</p>
 */
public enum TeamApplicationGroupMode {
    OFF,
    OPTIONAL,
    REQUIRED
}
