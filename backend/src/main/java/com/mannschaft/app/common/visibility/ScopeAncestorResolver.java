package com.mannschaft.app.common.visibility;

import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * スコープの「親組織 (ORGANIZATION)」をバッチ解決する共通ヘルパー。
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §5.1.1。</p>
 *
 * <p>実機の DB スキーマでは「TEAM が属する ORG」は {@code teams.organization_id}
 * ではなく {@code team_org_memberships} テーブル（status=ACTIVE）で管理される
 * ため、設計書の SQL 例とは Repository 経由先が異なるが、解決される
 * セマンティクス（TEAM → 親 ORG ID）は同等である。</p>
 *
 * <p>本クラスは {@link MembershipBatchQueryService} から呼ばれ、
 * {@code ORGANIZATION_WIDE} 公開判定と §11.6 連鎖ルール（親 ORG 非アクティブ）の
 * 共通土台を提供する。</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeAncestorResolver {

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    /**
     * スコープ集合に対して「親 ORGANIZATION ID」をバルク解決する。
     *
     * <p>挙動:</p>
     * <ul>
     *   <li>{@code TEAM} スコープ: {@code team_org_memberships} (status=ACTIVE) を
     *       1 SQL で IN 句解決し、見つかった team については {@code (TEAM, parentOrgId)}
     *       のエントリをマップに含める。所属組織が見つからない team は entry を返さない。</li>
     *   <li>{@code ORGANIZATION} スコープ: 自身の scopeId を親 ORG ID としてそのまま返す。</li>
     * </ul>
     *
     * <p>{@code scopes} が null/空、または TEAM が一切含まれない場合は SQL を発行しない。</p>
     *
     * @param scopes 解決対象のスコープ集合
     * @return スコープ → 親 ORG ID のマップ（不変ではない）
     */
    public Map<ScopeKey, Long> resolveParentOrgIds(Set<ScopeKey> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Map.of();
        }

        Set<Long> teamIds = new HashSet<>();
        for (ScopeKey s : scopes) {
            if ("TEAM".equals(s.scopeType())) {
                teamIds.add(s.scopeId());
            }
        }

        Map<Long, Long> teamToOrg = teamIds.isEmpty()
                ? Map.of()
                : teamOrgMembershipRepository.findOrganizationIdByTeamIdIn(teamIds);

        Map<ScopeKey, Long> result = new HashMap<>();
        for (ScopeKey s : scopes) {
            if ("TEAM".equals(s.scopeType())) {
                Long orgId = teamToOrg.get(s.scopeId());
                if (orgId != null) {
                    result.put(s, orgId);
                }
            } else if ("ORGANIZATION".equals(s.scopeType())) {
                result.put(s, s.scopeId());
            }
        }
        return result;
    }
}
