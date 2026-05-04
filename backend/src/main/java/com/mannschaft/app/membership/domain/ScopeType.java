package com.mannschaft.app.membership.domain;

/**
 * メンバーシップのスコープ種別。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。memberships.scope_type と positions.scope_type の
 * ENUM 値と一致する。プラットフォーム（platform）スコープは user_roles に残置するため、
 * 本 Enum には含めない。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.1 / §5.3</p>
 */
public enum ScopeType {

    /** 組織（organizations）スコープ。 */
    ORGANIZATION,

    /** チーム（teams）スコープ。 */
    TEAM
}
