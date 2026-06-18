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
 * {@link UserScopeRoleSnapshot} を、最小限の SQL 回数（最大 7 SQL）で構築する。
 * 設計書 {@code docs/features/F00_content_visibility_resolver.md} §10.2 / §11.6 / §15 D-14。</p>
 *
 * <p>設計書 D-14 の通り、{@code AccessControlService}（既存 12 メソッド）には手を入れず、
 * 本クラスをバルク判定専用 API として共通基盤側に新設している。</p>
 *
 * <p>SQL 数の上限（{@code orgWideScopes} 非空の最悪ケース）:</p>
 * <ol>
 *   <li>SystemAdmin 判定 1 回（{@code existsSystemAdminByUserId}）</li>
 *   <li>direct メンバーシップ（user_roles 権限ロール）1 回（{@code findByUserIdAndScopes}）</li>
 *   <li>direct メンバーシップで見つかった role_id → role_name の解決 1 回
 *       （{@code RoleRepository.findAllById}、空集合なら省略）</li>
 *   <li>{@code TEAM} → 親 ORG 解決 1 回（{@code TeamOrgMembershipRepository}）</li>
 *   <li>memberships の role_kind（MEMBER / SUPPORTER）を「direct スコープ ＋ 親 ORG」
 *       まとめて 1 回（{@code findActiveRoleKindsByUserAndScopes}、F00.5 §8.3）。
 *       direct / org で個別に引かず 1 バッチに統合している（SQL 数回帰の防止）。</li>
 *   <li>親 ORG の user_roles 権限ロール所属 1 回（{@code findByUserIdAndOrganizationIdIn}）</li>
 *   <li>非アクティブ親 ORG 抽出 1 回（{@code OrganizationRepository.findInactiveIdsByIdIn}、§11.6）</li>
 * </ol>
 *
 * <p>SystemAdmin 判定が hit した場合は早期 return で後続 SQL を一切発行しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipBatchQueryService {

    /**
     * フェーズ M2: 下向き再帰（{@code ORGANIZATION_AND_DESCENDANTS}）展開の最大深さ。
     * サイクル防止上限。M1 の
     * {@code OrganizationMembershipService.MAX_ORG_DESCENDANT_DEPTH}（= 32）と一致させ、
     * 配信 universe と可視性の評価範囲を揃える。
     */
    static final int ORG_DESCENDANT_MAX_DEPTH = 32;

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
        return snapshotForUser(userId, directScopes, orgWideScopes, Set.of());
    }

    /**
     * フェーズ M2: {@code descendantScopes}（{@code ORGANIZATION_AND_DESCENDANTS} 用の
     * 下向き再帰判定対象 ORG スコープ集合）を加えた拡張版。
     *
     * <p>{@code descendantScopes} が空のとき（＝従来の 3 引数版・新段を使わない Resolver）は
     * 下向き再帰 SQL を一切発行せず、生成される snapshot も従来 5 引数版と完全に同一であるため、
     * 既存挙動・SQL 数番人予算（最大 7）に影響しない。</p>
     *
     * <p>{@code descendantScopes} が非空のときのみ、{@code rootOrgIds} を集約して
     * {@link UserRoleRepository#findOrgRootsWhereUserIsDescendantMember} を
     * <b>1 バルク SQL</b> だけ追加発行する（{@code ORGANIZATION_WIDE} とは独立に集計し、
     * 新段 row が無ければ SQL 0）。非アクティブ判定（§11.6 鏡像）のため、対象 ORG を
     * {@code parentOrgs} へ {@code (ORGANIZATION, orgId) -> orgId} として合流させ、
     * {@code suspendedOrgIds} の抽出対象に含める。</p>
     *
     * @param userId          判定対象ユーザー（{@code null} 可: 匿名）
     * @param directScopes    直接所属判定の対象
     * @param orgWideScopes   {@code ORGANIZATION_WIDE}（上向き 1 段）判定の対象
     * @param descendantScopes {@code ORGANIZATION_AND_DESCENDANTS}（下向き再帰）判定の対象 ORG スコープ
     * @return 不変的に扱える {@link UserScopeRoleSnapshot}
     */
    public UserScopeRoleSnapshot snapshotForUser(
            Long userId,
            Set<ScopeKey> directScopes,
            Set<ScopeKey> orgWideScopes,
            Set<ScopeKey> descendantScopes) {
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
        Set<ScopeKey> safeDescendant = descendantScopes != null ? descendantScopes : Set.of();

        // SQL 2: direct メンバーシップの user_roles 権限ロールを取得（roles 解決込み）
        Map<ScopeKey, String> roleByScope = resolveDirectMembership(userId, safeDirect);

        // SQL 3 (orgWideScopes が非空のみ): TEAM → 親 ORG 解決
        Map<ScopeKey, Long> parentOrgs = safeOrgWide.isEmpty()
                ? new HashMap<>()
                : new HashMap<>(scopeAncestorResolver.resolveParentOrgIds(safeOrgWide));

        // 新段（ORGANIZATION_AND_DESCENDANTS）の根 ORG 集合。ORGANIZATION スコープのみ対象。
        Set<Long> descendantRootOrgIds = collectDescendantRootOrgIds(safeDescendant);
        // §11.6 鏡像: 新段の根 ORG 自身の非アクティブ判定のため parentOrgs に自身を合流させる
        //（ORG スコープは parentOrg=自身。ScopeAncestorResolver と同じ規約）。
        for (Long rootOrgId : descendantRootOrgIds) {
            parentOrgs.putIfAbsent(new ScopeKey("ORGANIZATION", rootOrgId), rootOrgId);
        }

        // SQL 4 (direct or 親 ORG が非空のみ): memberships の role_kind（MEMBER / SUPPORTER）を
        //        「direct スコープ ＋ 親 ORG」まとめて 1 バッチで取得する（F00.5 SQL 回帰根治）。
        //        direct 解決と org 解決で個別に membership を引くと snapshot あたり 2 SQL になり
        //        結合テストの SQL 数番人が回帰検知するため、ここで 1 SQL に統合し結果を使い回す。
        Set<Long> parentOrgIds = parentOrgs.isEmpty()
                ? Set.of()
                : new HashSet<>(parentOrgs.values());
        List<MembershipScopeRoleProjection> membershipRoleKinds =
                fetchMembershipRoleKinds(userId, safeDirect, parentOrgIds);

        // direct スコープに該当する MEMBER / SUPPORTER のみを roleByScope へマージする。
        // （親 ORG 専属の所属は roleByScope を汚さず orgMemberOf 側に振り分ける）
        applyDirectMembershipRoleKinds(safeDirect, membershipRoleKinds, roleByScope);

        // SQL 5 (parentOrgs が非空のみ): 親 ORG の user_roles 権限ロール所属を取得し、
        //        SQL 4 で取得済みの membership 由来 ORG 所属と UNION する。
        Set<ScopeKey> orgMemberOf = resolveOrgMembership(
                userId, parentOrgIds, membershipRoleKinds);

        // SQL 6 (parentOrgs が非空のみ): 非アクティブ親 ORG / 当該 ORG 抽出 §11.6（新段の根も含む）
        Set<Long> suspendedOrgIds = resolveInactiveParentOrgs(parentOrgs);

        // SQL 7 (descendantScopes が非空のみ): 下向き再帰メンバーシップを 1 バルク SQL で解決。
        //        ORGANIZATION_WIDE とは独立に発行し、新段 row が無ければ SQL 0。
        Set<Long> descendantMemberOfOrgIds = resolveDescendantMembership(userId, descendantRootOrgIds);

        return new UserScopeRoleSnapshot(
                false, roleByScope, parentOrgs, orgMemberOf, suspendedOrgIds, descendantMemberOfOrgIds);
    }

    /**
     * {@code descendantScopes}（ORGANIZATION スコープのみ有効）から根 ORG ID 集合を抽出する。
     * TEAM スコープが混入していても無視する（新段は ORG コンテンツ専用・G3）。
     */
    private Set<Long> collectDescendantRootOrgIds(Set<ScopeKey> descendantScopes) {
        if (descendantScopes.isEmpty()) {
            return Set.of();
        }
        Set<Long> rootOrgIds = new HashSet<>();
        for (ScopeKey s : descendantScopes) {
            if ("ORGANIZATION".equals(s.scopeType()) && s.scopeId() != null) {
                rootOrgIds.add(s.scopeId());
            }
        }
        return rootOrgIds;
    }

    /**
     * 下向き再帰メンバーシップを 1 バルク SQL で解決する（フェーズ M2）。
     *
     * <p>{@code rootOrgIds} が空のときは SQL を発行しない（空 IN () 回避 / SQL 0）。
     * SUPPORTER 除外は行わない（G7）。{@code maxDepth} は M1 と同じ
     * {@link com.mannschaft.app.organization.service.OrganizationMembershipService} の上限 32 を用いる。</p>
     */
    private Set<Long> resolveDescendantMembership(Long userId, Set<Long> rootOrgIds) {
        if (rootOrgIds.isEmpty()) {
            return Set.of();
        }
        List<Long> matchedRoots = userRoleRepository.findOrgRootsWhereUserIsDescendantMember(
                rootOrgIds, userId, ORG_DESCENDANT_MAX_DEPTH);
        if (matchedRoots.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(matchedRoots);
    }

    /**
     * directScopes に対する直接メンバーシップ（{@code user_roles} 由来の権限ロール）を取得し、
     * {@code ScopeKey → roleName} マップを構築する。
     *
     * <p>F00.5 §8.3 根治: ロールは次の 2 系統に分散している。</p>
     * <ul>
     *   <li><b>権限ロール</b>: {@code user_roles} 由来（ADMIN / DEPUTY_ADMIN / GUEST 等）… 本メソッド</li>
     *   <li><b>所属ロール</b>: {@code memberships.role_kind} 由来（MEMBER / SUPPORTER）…
     *       {@link #applyMembershipRoleKinds} が direct ＋ 親 ORG を 1 バッチで取得してマージ</li>
     * </ul>
     *
     * <p>memberships を統合しないと、user_roles から MEMBER / SUPPORTER が削除済み
     * （{@code V60.010}）であるため、memberships 専属の MEMBER / SUPPORTER が
     * {@code roleByScope} に入らず、SCOPE_AFFILIATED / SUPPORTERS_AND_ABOVE /
     * MEMBERS_AND_ABOVE が誤って不可視になる（過小権限バグ）。</p>
     */
    private Map<ScopeKey, String> resolveDirectMembership(Long userId, Set<ScopeKey> directScopes) {
        if (directScopes.isEmpty()) {
            return new HashMap<>();
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

        return result;
    }

    /**
     * memberships の所属ロール（MEMBER / SUPPORTER）を「direct スコープ ＋ 親 ORG」まとめて
     * <b>1 バッチ（1 SQL）</b>で取得する（F00.5 §8.3 / SQL 数回帰根治）。
     *
     * <p>direct 解決（{@link #resolveDirectMembership}）と org 解決
     * （{@link #resolveOrgMembership}）で個別に {@code findActiveRoleKindsByUserAndScopes} を
     * 引くと snapshot あたり 2 SQL に増え、結合テストの SQL 数番人が回帰検知する。
     * そのため direct teamIds / direct orgIds / 親 orgIds を 1 つの IN 句に集約して
     * 1 回だけクエリし、結果（projection リスト）を呼び出し側で direct / org へ振り分ける。
     * scope ごとのループ内クエリ（N+1）は一切発生しない。</p>
     *
     * @param userId       対象ユーザー
     * @param direct       direct スコープ（TEAM / ORGANIZATION）
     * @param parentOrgIds 親 ORG ID 集合
     * @return projection リスト（direct / 親 ORG 双方の MEMBER / SUPPORTER 行）。空集合 IN () を
     *         避けるため、対象スコープが皆無のときは空リストを返し SQL を発行しない。
     */
    private List<MembershipScopeRoleProjection> fetchMembershipRoleKinds(
            Long userId,
            Set<ScopeKey> direct,
            Set<Long> parentOrgIds) {

        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScopeKey s : direct) {
            if ("TEAM".equals(s.scopeType())) {
                teamIds.add(s.scopeId());
            } else if ("ORGANIZATION".equals(s.scopeType())) {
                orgIds.add(s.scopeId());
            }
        }
        // 親 ORG も同じ ORGANIZATION IN 句に合流させ、membership クエリを 1 回に統合する。
        orgIds.addAll(parentOrgIds);

        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return List.of();
        }

        // snapshot あたり唯一の membership クエリ。空集合 IN () 回避のため上で早期 return 済み。
        return membershipRepository.findActiveRoleKindsByUserAndScopes(userId, teamIds, orgIds);
    }

    /**
     * {@link #fetchMembershipRoleKinds} の結果から、<b>direct スコープに該当する</b>
     * MEMBER / SUPPORTER のみを {@code roleByScope} へマージする（F00.5 §8.3）。
     *
     * <p>親 ORG 専属の所属行は {@code roleByScope} を汚さないようここでは除外し、
     * {@link #resolveOrgMembership} 側で {@code orgMemberOf} に振り分ける。
     * これにより 1 バッチ統合後も従来と完全に同一のスナップショットを生成する。</p>
     */
    private void applyDirectMembershipRoleKinds(
            Set<ScopeKey> direct,
            List<MembershipScopeRoleProjection> membershipRoleKinds,
            Map<ScopeKey, String> roleByScope) {
        if (direct.isEmpty() || membershipRoleKinds.isEmpty()) {
            return;
        }
        for (MembershipScopeRoleProjection m : membershipRoleKinds) {
            if (m.getScopeType() == null || m.getScopeId() == null || m.getRoleKind() == null) {
                continue;
            }
            String scopeType = m.getScopeType().name(); // "TEAM" / "ORGANIZATION"
            ScopeKey scope = new ScopeKey(scopeType, m.getScopeId());
            if (!direct.contains(scope)) {
                continue; // 親 ORG 専属（direct でない）行はここでは扱わない
            }
            String roleName = m.getRoleKind().name(); // "MEMBER" / "SUPPORTER"
            mergeStrongerRole(roleByScope, scope, roleName);
        }
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
     *
     * <p>memberships 由来の所属は {@link #fetchMembershipRoleKinds} が direct ＋ 親 ORG を
     * 1 バッチで取得済みなので、本メソッドでは membership を再クエリせず、その projection リストから
     * 親 ORG に該当する ORGANIZATION 行を UNION する（SQL を増やさない）。
     * user_roles 由来の ORG 所属のみ別途 1 SQL で取得する。</p>
     */
    private Set<ScopeKey> resolveOrgMembership(
            Long userId,
            Set<Long> parentOrgIds,
            List<MembershipScopeRoleProjection> membershipRoleKinds) {
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

        // memberships 由来（MEMBER / SUPPORTER）。fetchMembershipRoleKinds が取得済みの
        // projection から、親 ORG に該当する ORGANIZATION 行を再クエリせず UNION する。
        for (MembershipScopeRoleProjection m : membershipRoleKinds) {
            if (m.getScopeId() != null
                    && m.getScopeType() == com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION
                    && parentOrgIds.contains(m.getScopeId())) {
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
