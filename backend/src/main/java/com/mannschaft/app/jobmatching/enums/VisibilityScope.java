package com.mannschaft.app.jobmatching.enums;

/**
 * 求人投稿の公開範囲。
 *
 * <p>F13.1 §5.2 の `visibility_scope` カラムに対応。
 * F01.7（カスタム公開範囲テンプレート）と連動する。</p>
 */
public enum VisibilityScope {

    /** チームメンバーのみ */
    TEAM_MEMBERS,

    /** チームメンバー + サポーター */
    TEAM_MEMBERS_SUPPORTERS,

    /** JOBBER 内部（JOBBER ロール保有者のみ、第三版で追加） */
    JOBBER_INTERNAL,

    /** JOBBER 総合掲示板（プラットフォーム横断公開、第三版で追加） */
    JOBBER_PUBLIC_BOARD,

    /** 組織スコープ（親組織全体に公開） */
    ORGANIZATION_SCOPE,

    /** F01.7 カスタム公開範囲テンプレート使用 */
    CUSTOM_TEMPLATE
}
