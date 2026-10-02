package com.mannschaft.app.auth.dto;

import java.util.List;

/**
 * F08.9 件2 保護者による子データ閲覧（所属チーム/組織）レスポンス。
 *
 * <p>{@code GET /api/v1/me/guardianship/children/{childUserId}/memberships} の返却。
 * 子（受益者）がアクティブに所属するチーム・組織を、scopeId と表示名で返す（camelCase 1:1）。
 * membership ドメインの Entity は漏らさず、ID から名称を合成した軽量 DTO のみを返す。</p>
 *
 * @param teams         所属チーム一覧
 * @param organizations 所属組織一覧
 */
public record GuardianChildMembershipsResponse(
        List<ScopeRef> teams,
        List<ScopeRef> organizations) {

    /**
     * スコープ参照（チーム/組織）。
     *
     * @param scopeId スコープ ID（teams.id / organizations.id）
     * @param name    表示名（{@code NameResolverService.resolveScopeName} で解決）
     */
    public record ScopeRef(Long scopeId, String name) {
    }
}
