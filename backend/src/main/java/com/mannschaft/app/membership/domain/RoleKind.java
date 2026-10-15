package com.mannschaft.app.membership.domain;

/**
 * メンバーシップの種別（メンバー / サポーター）。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。memberships.role_kind の ENUM 値と一致する。</p>
 *
 * <ul>
 *   <li>{@link #MEMBER}: 招待トークン受理または ADMIN 直接付与による正式メンバー</li>
 *   <li>{@link #SUPPORTER}: フォロー型のサポーター（招待コード不要の自己登録モード）</li>
 * </ul>
 *
 * <p>権限ロール（SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST）は user_roles 側の責務。
 * 本 Enum と権限ロールは別概念であり、混同しないこと。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §2.3 / §5.1</p>
 */
public enum RoleKind {

    /** 正式メンバー。 */
    MEMBER,

    /** フォロー型のサポーター。 */
    SUPPORTER
}
