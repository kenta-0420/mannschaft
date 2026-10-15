package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;

/**
 * アクティブメンバーシップの「スコープ × role_kind」軽量射影。
 *
 * <p>F00.5 §8.3 根治: 所属ロール（MEMBER / SUPPORTER）の判定を memberships に統合する際、
 * 複数スコープ分の有効な {@link RoleKind} を 1 SQL でまとめ取りする
 * （{@code MembershipBatchQueryService} の N+1 回避）ために用いる。</p>
 *
 * <p>メソッド名は {@link com.mannschaft.app.membership.entity.MembershipEntity} の
 * getter と一致させること（Spring Data JPA の Interface Projection 規約）。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §8.3</p>
 */
public interface MembershipScopeRoleProjection {

    /** スコープ種別（TEAM / ORGANIZATION）。 */
    ScopeType getScopeType();

    /** スコープ ID（team_id または organization_id）。 */
    Long getScopeId();

    /** メンバー区分（MEMBER / SUPPORTER）。 */
    RoleKind getRoleKind();
}
