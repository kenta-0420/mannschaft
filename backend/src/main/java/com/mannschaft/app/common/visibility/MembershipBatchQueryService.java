package com.mannschaft.app.common.visibility;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleProjection;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F00 共通可視性基盤のメンバーシップバッチ照会サービス。
 *
 * <p>1 リクエスト内で複数の {@code ContentVisibilityResolver} が共有する
 * {@link UserScopeRoleSnapshot} を、最小限の SQL 回数（最大 5 SQL）で構築する。
 * 設計書 {@code docs/features/F00_content_visibility_resolver.md} §10.2 / §11.6 / §15 D-14。</p>
 *
 * <p>設計書 D-14 の通り、{@code AccessControlService}（既存 12 メソッド）には手を入れず、
 * 本クラスをバルク判定専用 API として共通基盤側に新設している。</p>
 *
 * <p>SQL 数の上限（{@code orgWideScopes} 非空の最悪ケース）:</p>
 * <ol>
 *   <li>SystemAdmin 判定 1 回（{@code existsSystemAdminByUserId}）</li>
 *   <li>direct メンバーシップ 1 回（{@code findByUserIdAndScopes}）</li>
 *   <li>direct メンバーシップで見つかった role_id → role_name の解決 1 回
 *       （{@code RoleRepository.findAllById}、空集合なら省略）</li>
 *   <li>{@code TEAM} → 親 ORG 解決 1 回（{@code TeamOrgMembershipRepository}）</li>
 *   <li>親 ORG メンバーシップ 1 回（{@code findByUserIdAndOrganizationIdIn}）</li>
 *   <li>非アクティブ親 ORG 抽出 1 回（{@code OrganizationRepository.findInactiveIdsByIdIn}、§11.6）</li>
 * </ol>
 *
 * <p>SystemAdmin 判定が hit した場合は早期 return で後続 SQL を一切発行しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipBatchQueryService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final ScopeAncestorResolver scopeAncestorResolver;
    private final OrganizationRepository organizationRepository;

    /**
     * ユーザー × 複数スコープのメンバーシップ・ロール情報を最小 SQL で取得する。
     *
     * <p>呼び出しパターン:</p>
     * <ul>
     *   <li>{@code userId == null}（匿名）→ {@link UserScopeRoleSnapshot#empty()} を返す（SQL 0 回）</li>
     *   <li>SystemAdmin → {@link UserScopeRoleSnapshot#systemAdmin()} を返す（SQL 1 回）</li>
     *   <li>それ以外 → directScopes / orgWideScopes に応じた SQL を発行</li>
     * </ul>
     *
     * @param userId         判定対象ユーザー（{@code null} 可: 匿名）
     * @param directScopes   直接所属判定の対象（MEMBERS_ONLY/SUPPORTERS_AND_ABOVE/ADMINS_ONLY 用）
     * @param orgWideScopes  ORGANIZATION_WIDE 判定の対象スコープ集合
     * @return 不変的に扱える {@link UserScopeRoleSnapshot}
     */
    public UserScopeRoleSnapshot snapshotForUser(
            Long userId,
            Set<ScopeKey> directScopes,
            Set<ScopeKey> orgWideScopes) {
        if (userId == null) {
            return UserScopeRoleSnapshot.empty();
        }

        // SQL 1: SystemAdmin 判定（既存メソッド戻り値は long、>0 で SystemAdmin）
        boolean sysAdmin = userRoleRepository.existsSystemAdminByUserId(userId) > 0;
        if (sysAdmin) {
            return UserScopeRoleSnapshot.forSystemAdmin();
        }

        Set<ScopeKey> safeDirect = directScopes != null ? directScopes : Set.of();
        Set<ScopeKey> safeOrgWide = orgWideScopes != null ? orgWideScopes : Set.of();

        // SQL 2: direct メンバーシップ取得（teamIds と organizationIds に分離して呼ぶ）
        Map<ScopeKey, String> roleByScope = resolveDirectMembership(userId, safeDirect);

        // SQL 3 (orgWideScopes が非空のみ): TEAM → 親 ORG 解決
        Map<ScopeKey, Long> parentOrgs = safeOrgWide.isEmpty()
                ? Map.of()
                : scopeAncestorResolver.resolveParentOrgIds(safeOrgWide);

        // SQL 4 (parentOrgs が非空のみ): 親 ORG メンバーシップ取得
        Set<ScopeKey> orgMemberOf = resolveOrgMembership(userId, parentOrgs);

        // SQL 5 (parentOrgs が非空のみ): 非アクティブ親 ORG 抽出 §11.6
        Set<Long> suspendedOrgIds = resolveInactiveParentOrgs(parentOrgs);

        return new UserScopeRoleSnapshot(false, roleByScope, parentOrgs, orgMemberOf, suspendedOrgIds);
    }

    /**
     * directScopes に対する直接メンバーシップを取得し、{@code ScopeKey → roleName} マップを構築する。
     */
    private Map<ScopeKey, String> resolveDirectMembership(Long userId, Set<ScopeKey> directScopes) {
        if (directScopes.isEmpty()) {
            return Map.of();
        }

        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScopeKey s : directScopes) {
            if ("TEAM".equals(s.scopeType())) {
                teamIds.add(s.scopeId());
            } else if ("ORGANIZATION".equals(s.scopeType())) {
                orgIds.add(s.scopeId());
            }
        }

        // SQL: user_roles の direct メンバーシップ（A-3a の仕様）
        List<UserRoleProjection> directRoles = userRoleRepository.findByUserIdAndScopes(
                userId, teamIds, orgIds);
        if (directRoles.isEmpty()) {
            return Map.of();
        }

        // role_id → role_name の解決（roles テーブルへバルク 1 SQL）
        Set<Long> roleIds = new HashSet<>();
        for (UserRoleProjection p : directRoles) {
            if (p.getRoleId() != null) {
                roleIds.add(p.getRoleId());
            }
        }
        Map<Long, String> roleIdToName = resolveRoleNames(roleIds);

        Map<ScopeKey, String> result = new HashMap<>();
        for (UserRoleProjection p : directRoles) {
            String roleName = roleIdToName.get(p.getRoleId());
            if (roleName == null) {
                // 不整合（FK 違反）。fail-closed の原則からスキップする。
                continue;
            }
            if (p.getTeamId() != null) {
                result.put(new ScopeKey("TEAM", p.getTeamId()), roleName);
            } else if (p.getOrganizationId() != null) {
                result.put(new ScopeKey("ORGANIZATION", p.getOrganizationId()), roleName);
            }
        }
        return result;
    }

    /**
     * roleIds から role_name を 1 SQL で解決する。空集合なら SQL を発行しない。
     */
    private Map<Long, String> resolveRoleNames(Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>(roleIds.size());
        for (RoleEntity r : roleRepository.findAllById(roleIds)) {
            map.put(r.getId(), r.getName());
        }
        return map;
    }

    /**
     * 親 ORG マップから「ユーザーがメンバーである ORGANIZATION スコープ」集合を返す。
     */
    private Set<ScopeKey> resolveOrgMembership(Long userId, Map<ScopeKey, Long> parentOrgs) {
        if (parentOrgs.isEmpty()) {
            return Set.of();
        }
        Set<Long> parentOrgIds = new HashSet<>(parentOrgs.values());
        if (parentOrgIds.isEmpty()) {
            return Set.of();
        }
        List<UserRoleProjection> orgMembers = userRoleRepository.findByUserIdAndOrganizationIdIn(
                userId, parentOrgIds);
        if (orgMembers.isEmpty()) {
            return Set.of();
        }
        Set<ScopeKey> result = new HashSet<>(orgMembers.size());
        for (UserRoleProjection p : orgMembers) {
            if (p.getOrganizationId() != null) {
                result.add(new ScopeKey("ORGANIZATION", p.getOrganizationId()));
            }
        }
        return result;
    }

    /**
     * 親 ORG マップから「非アクティブな組織 ID」集合を返す（§11.6）。
     *
     * <p>現状 {@code organizations} テーブルに SUSPENDED 列は無く、
     * {@code deleted_at IS NOT NULL}（論理削除済）のみが「非アクティブ」となる。
     * SUSPENDED 概念が DB に追加されたら、{@code OrganizationRepository.findInactiveIdsByIdIn}
     * のクエリ側で OR 条件を追加すれば本サービスは無改修で追従する。</p>
     */
    private Set<Long> resolveInactiveParentOrgs(Map<ScopeKey, Long> parentOrgs) {
        if (parentOrgs.isEmpty()) {
            return Set.of();
        }
        Set<Long> parentOrgIds = new HashSet<>(parentOrgs.values());
        if (parentOrgIds.isEmpty()) {
            return Set.of();
        }
        List<Long> inactive = organizationRepository.findInactiveIdsByIdIn(parentOrgIds);
        if (inactive.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(inactive);
    }
}
