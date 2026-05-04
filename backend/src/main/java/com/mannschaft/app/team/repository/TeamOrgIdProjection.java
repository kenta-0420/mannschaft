package com.mannschaft.app.team.repository;

/**
 * 「チーム ID → 親組織 ID」軽量射影。
 *
 * <p>F00 ContentVisibilityResolver 基盤の {@code ScopeAncestorResolver} から、
 * {@code TeamOrgMembershipRepository#findOrganizationIdByTeamIdIn} 経由で
 * バルク解決のために利用される。</p>
 *
 * <p>F00 設計書 §5.1.1 / §10.2 参照。</p>
 */
public interface TeamOrgIdProjection {

    /** チーム ID。 */
    Long getTeamId();

    /** 当該チームが所属する組織 ID。 */
    Long getOrganizationId();
}
