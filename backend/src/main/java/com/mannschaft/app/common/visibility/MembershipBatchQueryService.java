package com.mannschaft.app.common.visibility;

import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.MembershipScopeRoleProjection;
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
    private final MembershipRepository membershipRepository;

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
     *
     * <p>F00.5 §8.3 根治: ロールは次の 2 系統に分散しているため、両方を UNION して
     * priority 最小（最強）を採用する。</p>
     * <ul>
     *   <li><b>権限ロール</b>: {@code user_roles} 由来（ADMIN / DEPUTY_ADMIN / GUEST 等）</li>
     *   <li><b>所属ロール</b>: {@code memberships.role_kind} 由来（MEMBER / SUPPORTER）</li>
     * </ul>
     *
     * <p>memberships を統合しないと、user_roles から MEMBER / SUPPORTER が削除済み
     * （{@code V60.010}）であるため、memberships 専属の MEMBER / SUPPORTER が
     * {@code roleByScope} に入らず、SCOPE_AFFILIATED / SUPPORTERS_AND_ABOVE /
     * MEMBERS_AND_ABOVE が誤って不可視になる（過小権限バグ）。</p>
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

        Map<ScopeKey, String> result = new HashMap<>();

        // SQL A: user_roles の direct 権限ロール（A-3a の仕様）
        List<UserRoleProjection> directRoles = userRoleRepository.findByUserIdAndScopes(
                userId, teamIds, orgIds);
        if (!directRoles.isEmpty()) {
            // role_id → role_name の解決（roles テーブルへバルク 1 SQL）
            Set<Long> roleIds = new HashSet<>();
            for (UserRoleProjection p : directRoles) {
                if (p.getRoleId() != null) {
                    roleIds.add(p.getRoleId());
                }
            }
            Map<Long, String> roleIdToName = resolveRoleNames(roleIds);

            for (UserRoleProjection p : directRoles) {
                String roleName = roleIdToName.get(p.getRoleId());
                if (roleName == null) {
                    // 不整合（FK 違反）。fail-closed の原則からスキップする。
                    continue;
                }
                if (p.getTeamId() != null) {
                    mergeStrongerRole(result, new ScopeKey("TEAM", p.getTeamId()), roleName);
                } else if (p.getOrganizationId() != null) {
                    mergeStrongerRole(result, new ScopeKey("ORGANIZATION", p.getOrganizationId()), roleName);
                }
            }
        }

        // SQL B (F00.5 §8.3): memberships の direct 所属ロール（MEMBER / SUPPORTER）をマージ
        if (!teamIds.isEmpty() || !orgIds.isEmpty()) {
            List<MembershipScopeRoleProjection> memberships =
                    membershipRepository.findActiveRoleKindsByUserAndScopes(userId, teamIds, orgIds);
            for (MembershipScopeRoleProjection m : memberships) {
                if (m.getScopeType() == null || m.getScopeId() == null || m.getRoleKind() == null) {
                    continue;
                }
                String scopeType = m.getScopeType().name(); // "TEAM" / "ORGANIZATION"
                String roleName = m.getRoleKind().name();    // "MEMBER" / "SUPPORTER"
                mergeStrongerRole(result, new ScopeKey(scopeType, m.getScopeId()), roleName);
            }
        }

        return result;
    }

    /**
     * 同一スコープに対し、priority がより強い（数値が小さい）ロール名を採用してマージする。
     * 既存値が無いか、新ロールの方が強ければ上書きする。
     */
    private void mergeStrongerRole(Map<ScopeKey, String> map, ScopeKey scope, String candidateRole) {
        String existing = map.get(scope);
        if (existing == null || RolePriority.priority(candidateRole) < RolePriority.priority(existing)) {
            map.put(scope, candidateRole);
        }
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
     *
     * <p>F00.5 §8.3 根治: ORGANIZATION_WIDE 判定の親 ORG 所属も、user_roles（権限ロール）に加えて
     * memberships（MEMBER / SUPPORTER）由来の所属を UNION する。これにより memberships 専属で
     * 親 ORG に所属するユーザーが ORGANIZATION_WIDE コンテンツを閲覧できなくなる過小権限バグを防ぐ。</p>
     */
    private Set<ScopeKey> resolveOrgMembership(Long userId, Map<ScopeKey, Long> parentOrgs) {
        if (parentOrgs.isEmpty()) {
            return Set.of();
        }
        Set<Long> parentOrgIds = new HashSet<>(parentOrgs.values());
        if (parentOrgIds.isEmpty()) {
            return Set.of();
        }

        Set<ScopeKey> result = new HashSet<>();

        // user_roles 由来（ADMIN / DEPUTY_ADMIN / GUEST 等の権限ロール行）
        List<UserRoleProjection> orgMembers = userRoleRepository.findByUserIdAndOrganizationIdIn(
                userId, parentOrgIds);
        for (UserRoleProjection p : orgMembers) {
            if (p.getOrganizationId() != null) {
                result.add(new ScopeKey("ORGANIZATION", p.getOrganizationId()));
            }
        }

        // memberships 由来（MEMBER / SUPPORTER）。teamIds は空集合で照会。
        List<MembershipScopeRoleProjection> orgMemberships =
                membershipRepository.findActiveRoleKindsByUserAndScopes(userId, Set.of(), parentOrgIds);
        for (MembershipScopeRoleProjection m : orgMemberships) {
            if (m.getScopeId() != null
                    && m.getScopeType() == com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION) {
                result.add(new ScopeKey("ORGANIZATION", m.getScopeId()));
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
