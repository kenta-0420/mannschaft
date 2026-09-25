package com.mannschaft.app.role.domain;

/**
 * アーカイブ退避した権限付与の種別。
 *
 * <p>{@code archived_membership_grants.grant_type} の写像。{@code grant_ref_id} が
 * どのテーブルの ID を指すかを決める（ROLE → roles.id / PERMISSION_GROUP → permission_groups.id）。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.3</p>
 */
public enum GrantType {

    /** roles.id を指す。 */
    ROLE,

    /** permission_groups.id を指す。 */
    PERMISSION_GROUP
}
