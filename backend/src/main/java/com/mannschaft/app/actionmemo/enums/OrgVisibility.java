package com.mannschaft.app.actionmemo.enums;

/**
 * F02.5 Phase 4-α 組織スコープ公開範囲。
 *
 * <p>{@code organization_id} が設定された場合のみ有効。
 * NULL の場合は個人/チームスコープのみで扱われ、組織タイムラインには現れない。</p>
 */
public enum OrgVisibility {

    /** 組織内のチームメンバーのみ閲覧可能（デフォルト） */
    TEAM_ONLY,

    /** 組織全体（全メンバー）に公開 */
    ORG_WIDE
}
