package com.mannschaft.app.visibility;

/**
 * 公開範囲テンプレートのルール種別。
 * 各ルールは OR 結合で評価される。
 */
public enum VisibilityTemplateRuleType {

    /** 指定チームのフレンドチームメンバー全員 */
    TEAM_FRIEND_OF,

    /** 指定組織の全メンバー */
    ORGANIZATION_MEMBER_OF,

    /** 指定チームのメンバー */
    TEAM_MEMBER_OF,

    /** 指定地域と属性合致するチーム所属ユーザー */
    REGION_MATCH,

    /** 明示指定チームのメンバー */
    EXPLICIT_TEAM,

    /** 明示指定ユーザー */
    EXPLICIT_USER,

    /** 明示指定 social_profile のユーザー */
    EXPLICIT_SOCIAL_PROFILE
}
