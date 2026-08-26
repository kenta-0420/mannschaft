package com.mannschaft.app.common;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * アクセス制御の共通ヘルパーサービス。
 * メンバーシップ検証・ロール判定・権限チェックを一元的に提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccessControlService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RoleService roleService;
    private final UserCareLinkRepository userCareLinkRepository;
    private final MembershipRepository membershipRepository;

    /**
     * 欠陥Z 根治: 組織発コンテンツの応答・要対応集計の認可で「配下チーム所属」を含めるための越境窓口。
     *
     * <p>配下チームのみ所属するユーザーは組織に直接 {@code user_roles}/{@code memberships} を持たないため、
     * 直接所属のみを見る {@link #isMember} では弾かれる（実機 403 = COMMON_002 の真因）。
     * organization ドメインの {@code team_org_memberships}/{@code organizations} 再帰展開を直接参照せず、
     * Service メソッド呼び出し経由（CLAUDE.md ドメイン境界の原則・M2 #1644 と同じ越境窓口方式）で解決する。</p>
     */
    private final OrganizationMembershipService organizationMembershipService;

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

    /** ORG 再帰展開のサイクル防止上限（{@code OrgFanoutRecipientSource.MAX_ORG_DESCENDANT_DEPTH} と同値）。 */
    private static final int ORG_DESCENDANT_MAX_DEPTH = 32;

    // ========================================
    // メンバーシップ検証
    // ========================================

    /**
     * ユーザーがスコープのメンバーであることを検証する。非メンバーは403。
     */
    public void checkMembership(Long userId, Long scopeId, String scopeType) {
        if (!isMember(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ユーザーがスコープのメンバーかどうかを返す。
     *
     * <p>F00.5 Phase 3: memberships テーブルを参照する（旧 user_roles 参照から切替）。
     * ADMIN/DEPUTY_ADMIN 等の権限ロール判定は引き続き user_roles 参照（isAdminOrAbove 等）。</p>
     */
    public boolean isMember(Long userId, Long scopeId, String scopeType) {
        ScopeType scope = ScopeType.valueOf(scopeType);
        return membershipRepository.existsActiveByUserAndScope(userId, scope, scopeId);
    }

    /**
     * ユーザーがスコープのメンバー、または（ORGANIZATION スコープのとき）その配下ツリーの
     * <b>応答母集団メンバー</b>かどうかを返す（欠陥Z 根治）。
     *
     * <p>組織発コンテンツ（出欠/アンケート）の応答・要対応集計では、配下チームのみに所属する
     * メンバーも組織コンテンツに回答できる必要がある（マスター御裁可①）。しかし配下チーム所属者は
     * 組織に直接 {@code memberships} を持たないため {@link #isMember} では false になり、実機で 403 になる。
     * 本メソッドは:</p>
     * <ul>
     *   <li>{@code ORGANIZATION} スコープ: {@code isMember(...) ||
     *       organizationMembershipService.isActiveMemberInOrgDistributionUniverse(scopeId, userId)}。
     *       後者は<b>純 SUPPORTER を除外</b>する（マスター御裁可②: 純 SUPPORTER は回答不可）。</li>
     *   <li>{@code TEAM} 等その他: 従来どおり {@link #isMember}（配下概念を持ち込まない・挙動不変）。</li>
     * </ul>
     *
     * <p>{@link #isMember}（直接所属のみ）の既存定義は変更しない。本メソッドは応答・要対応の
     * 認可入口専用に新設し、可視性層（F00）は別途 M2 #1644 で配下開放済みである。</p>
     *
     * @param userId    操作ユーザー
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param scopeType スコープ種別（"TEAM" または "ORGANIZATION"）
     * @return メンバー、または ORGANIZATION 配下の応答母集団メンバー（純 SUPPORTER 除く）なら true
     */
    public boolean isMemberOrDescendant(Long userId, Long scopeId, String scopeType) {
        // 既存 3 引数版は純 SUPPORTER 除外（includeSupporters=false）に委譲して挙動温存（#1647 非回帰）。
        return isMemberOrDescendant(userId, scopeId, scopeType, false);
    }

    /**
     * {@link #isMemberOrDescendant(Long, Long, String)} の {@code includeSupporters} トグル版
     * （配信＝受信権 統一・関所(3)回答）。
     *
     * <p>組織発コンテンツ（出欠/アンケート）は、コンテンツの {@code includeSupporters} トグルに従って
     * 配信母集団が決まる（ON=配下 SUPPORTER 含む / OFF=配下 MEMBER のみ）。応答（回答）の認可母集団も
     * これと一致させるため、本オーバーロードは ORGANIZATION スコープで
     * {@link OrganizationMembershipService#isInOrgDistributionAudience(Long, Long, boolean)}
     * を呼び、トグルに応じて純 SUPPORTER の救済有無を切り替える。</p>
     *
     * <ul>
     *   <li>{@code includeSupporters=false}: 純 SUPPORTER を除外（既存 3 引数版と同一・#1647 非回帰）。</li>
     *   <li>{@code includeSupporters=true}: 配下 SUPPORTER も応答母集団に含む（トグル ON のコンテンツ）。</li>
     * </ul>
     *
     * <p>TEAM 等その他スコープは従来どおり {@link #isMember}（配下概念を持ち込まない・挙動不変）。</p>
     *
     * @param userId            操作ユーザー
     * @param scopeId           スコープ ID（チーム ID または組織 ID）
     * @param scopeType         スコープ種別（"TEAM" または "ORGANIZATION"）
     * @param includeSupporters コンテンツの配信トグル（true=配下 SUPPORTER 含む / false=純 SUPPORTER 除外）
     * @return メンバー、または ORGANIZATION 配下のトグル準拠配信母集団メンバーなら true
     */
    public boolean isMemberOrDescendant(Long userId, Long scopeId, String scopeType, boolean includeSupporters) {
        if (isMember(userId, scopeId, scopeType)) {
            return true;
        }
        if ("ORGANIZATION".equals(scopeType)) {
            return organizationMembershipService.isInOrgDistributionAudience(scopeId, userId, includeSupporters);
        }
        return false;
    }

    /**
     * ユーザーがスコープのメンバー、または ORGANIZATION 配下ツリーの応答母集団メンバーであることを
     * 検証する。いずれでもない場合は 403（COMMON_002）。
     *
     * <p>{@link #checkMembership} の配下対応版（欠陥Z 根治）。組織発コンテンツの応答・要対応集計の
     * 入口でのみ使用する。例外コードは既存 {@link #checkMembership} と同一（COMMON_002）。</p>
     *
     * @see #isMemberOrDescendant(Long, Long, String)
     */
    public void checkMembershipOrDescendant(Long userId, Long scopeId, String scopeType) {
        checkMembershipOrDescendant(userId, scopeId, scopeType, false);
    }

    /**
     * {@link #checkMembershipOrDescendant(Long, Long, String)} の {@code includeSupporters} トグル版。
     * いずれの母集団にも属さない場合は 403（COMMON_002）。
     *
     * <p>集約入口（action-required 等）では、トグル ON で配信された出欠/アンケに回答可能な配下 SUPPORTER も
     * 入口で弾かれないよう {@code includeSupporters=true} で広めに通す（per-content の絞りは各未回答クエリの
     * materialize 済み行で自然に効く）。単一コンテンツの回答経路は当該コンテンツのトグル値を渡す。</p>
     *
     * @see #isMemberOrDescendant(Long, Long, String, boolean)
     */
    public void checkMembershipOrDescendant(Long userId, Long scopeId, String scopeType,
                                            boolean includeSupporters) {
        if (!isMemberOrDescendant(userId, scopeId, scopeType, includeSupporters)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // ロール判定
    // ========================================

    /**
     * ユーザーのスコープ内ロール名を取得する。メンバーでない場合はnull。
     *
     * <p>F00.5 §8.3 根治: 所属ロール（MEMBER / SUPPORTER）は user_roles から削除済み
     * （{@code V60.010__delete_member_supporter_from_user_roles.sql}）のため、memberships を
     * 統合した {@link #resolveEffectiveRoleName} に委譲する。これにより memberships 専属の
     * MEMBER / SUPPORTER も正しいロール名で返るようになり、過小権限バグが解消される。</p>
     */
    public String getRoleName(Long userId, Long scopeId, String scopeType) {
        return resolveEffectiveRoleName(userId, scopeId, scopeType);
    }

    /**
     * ユーザーのスコープ内「有効ロール名」を解決する（F00.5 §8.3 統合ロール解決）。
     *
     * <p>F00.5 で所属（memberships）と権限ロール（user_roles）が分離されたことに伴い、
     * 1 スコープに対するユーザーのロールは次の 2 系統に分散している:</p>
     * <ul>
     *   <li><b>プラットフォームロール</b>: {@code user_roles} のスコープ未指定行
     *       （SYSTEM_ADMIN）</li>
     *   <li><b>権限ロール</b>: {@code user_roles} 由来（ADMIN / DEPUTY_ADMIN / GUEST など）</li>
     *   <li><b>所属ロール</b>: {@code memberships.role_kind} 由来（MEMBER / SUPPORTER）</li>
     * </ul>
     *
     * <p>本メソッドは両系統のロール名を集め、{@code roles} テーブルの priority が最小
     * （= 最強）のロール名を返す。どちらにも該当が無ければ {@code null} を返す。</p>
     *
     * <p>例:</p>
     * <ul>
     *   <li>memberships のみ MEMBER → {@code "MEMBER"}</li>
     *   <li>memberships のみ SUPPORTER → {@code "SUPPORTER"}</li>
     *   <li>user_roles ADMIN ＋ memberships MEMBER → {@code "ADMIN"}（priority 最強を採用）</li>
     *   <li>user_roles GUEST のみ → {@code "GUEST"}</li>
     * </ul>
     *
     * <p>本メソッドは両 Repository を<b>読むのみ・書かない</b>（F00.5 §13 境界原則）。</p>
     *
     * @param userId    操作ユーザー
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param scopeType スコープ種別（"TEAM" または "ORGANIZATION"）
     * @return 有効ロール名。該当が無ければ {@code null}
     */
    public String resolveEffectiveRoleName(Long userId, Long scopeId, String scopeType) {
        return resolveEffectiveRole(userId, scopeId, scopeType)
                .map(EffectiveRole::name)
                .orElse(null);
    }

    /**
     * 有効ロール（名前＋priority）を解決する内部ヘルパー。
     *
     * <p>user_roles 由来の権限ロールと memberships.role_kind 由来の所属ロールを集め、
     * priority が最小（最強）のものを返す。{@link #resolveEffectiveRoleName} および
     * {@link #hasRoleOrAbove} が共有する。priority を直接保持して返すため、
     * 呼び出し側で追加の {@code roleRepository.findByName} を発行する必要がない
     * （= 既存テストのスタブ前提を壊さない）。</p>
     */
    private Optional<EffectiveRole> resolveEffectiveRole(Long userId, Long scopeId, String scopeType) {
        // SYSTEM_ADMIN は team_id / organization_id がともに null のプラットフォームロール。
        // スコープ別 findUserRole() では取得できないため、最強ロールとして先に解決する。
        if (isSystemAdmin(userId)) {
            return Optional.of(new EffectiveRole("SYSTEM_ADMIN", 1));
        }

        EffectiveRole best = null;

        // 1) 権限ロール（user_roles 由来: ADMIN / DEPUTY_ADMIN / GUEST 等）
        Optional<RoleEntity> userRole = findUserRole(userId, scopeId, scopeType)
                .flatMap(ur -> roleRepository.findById(ur.getRoleId()));
        if (userRole.isPresent()) {
            best = new EffectiveRole(userRole.get().getName(), userRole.get().getPriority());
        }

        // 2) 所属ロール（memberships.role_kind 由来: MEMBER / SUPPORTER）
        ScopeType scope = ScopeType.valueOf(scopeType);
        List<RoleKind> activeRoleKinds = membershipRepository.findActiveRoleKinds(userId, scope, scopeId);
        for (RoleKind roleKind : activeRoleKinds) {
            if (roleKind == null) {
                continue;
            }
            String candidateName = roleKind.name(); // "MEMBER" / "SUPPORTER"（roles.name と一致）
            int candidatePriority = roleRepository.findByName(candidateName)
                    .map(RoleEntity::getPriority)
                    .orElse(Integer.MAX_VALUE);
            if (best == null || candidatePriority < best.priority()) {
                best = new EffectiveRole(candidateName, candidatePriority);
            }
        }

        return Optional.ofNullable(best);
    }

    /** 有効ロールの名前と priority を束ねる内部値オブジェクト。 */
    private record EffectiveRole(String name, int priority) {
    }

    /**
     * 「単一スコープ × 複数ユーザー」向けの有効ロール名一括解決（F03.16 §4.5.0 段1）。
     *
     * <p>{@link #resolveEffectiveRoleName} と<b>完全に同一の規則</b>
     * （権限ロール（{@code user_roles}）と所属ロール（{@code memberships.role_kind}）の
     * 両系統を集め、{@link com.mannschaft.app.common.visibility.RolePriority} が最強
     * （priority 最小）のロール名を採る）で、候補者数に依らず<b>SQL 2 本</b>で解決する。
     * {@link #resolveEffectiveRoleName} を候補者ごとに呼ぶと候補者数に比例して SQL が増える
     * ため、一括版が必要な呼び出し元（{@code schedule} ドメインのメンション可視性フィルタ等）
     * 向けに本メソッドを設ける。</p>
     *
     * <p>本メソッドは {@code common}（共有ドメイン）に属するため、{@code role}/{@code membership}
     * ドメインの Repository・射影型を他ドメインへ一切漏らさない
     * （ArchUnit D-1/D-5 準拠の公開窓口。{@code schedule} ドメインの
     * {@code ScheduleCommentViewerFilter} が本メソッド経由でロール解決する）。</p>
     *
     * @param userIds   候補ユーザー ID 集合
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param scopeType スコープ種別（"TEAM" または "ORGANIZATION"）
     * @return Map(userId → 有効ロール名)。両系統いずれにも該当が無いユーザーは含まれない
     */
    public Map<Long, String> resolveEffectiveRoleNames(
            java.util.Collection<Long> userIds, Long scopeId, String scopeType) {
        Map<Long, String> roleByUser = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty() || scopeId == null || scopeType == null) {
            return roleByUser;
        }
        boolean team = "TEAM".equals(scopeType);

        // SQL 1: 権限ロール（ADMIN / DEPUTY_ADMIN / GUEST 等）。
        List<com.mannschaft.app.common.visibility.ScopeUserRoleProjection> permissionRoles = team
                ? userRoleRepository.findScopeRolesByTeamIdAndUserIdIn(scopeId, userIds)
                : userRoleRepository.findScopeRolesByOrganizationIdAndUserIdIn(scopeId, userIds);
        for (com.mannschaft.app.common.visibility.ScopeUserRoleProjection row : permissionRoles) {
            mergeStrongestRole(roleByUser, row.getUserId(), row.getRoleName());
        }

        // SQL 2: 所属ロール（MEMBER / SUPPORTER）。
        ScopeType scope = team ? ScopeType.TEAM : ScopeType.ORGANIZATION;
        List<MembershipRepository.MembershipUserRoleKindProjection> membershipRoles =
                membershipRepository.findActiveRoleKindsByScopeAndUsers(scope, scopeId, userIds);
        for (MembershipRepository.MembershipUserRoleKindProjection row : membershipRoles) {
            if (row.getRoleKind() != null) {
                mergeStrongestRole(roleByUser, row.getUserId(), row.getRoleKind().name());
            }
        }
        return roleByUser;
    }

    /**
     * F03.16 是正3【P2】: {@link #resolveEffectiveRoleNames} の ORGANIZATION 版に、配下チーム経由の
     * 所属ロールを合成した版。
     *
     * <p>{@link #resolveEffectiveRoleNames} は {@code memberships.scope_type = 'ORGANIZATION' AND
     * scope_id = :organizationId} の<b>直接所属のみ</b>を見るため、配下チームのみに所属するメンバーは
     * ロールが解決できず {@code null} になる（{@code MinViewRoleThreshold.satisfies} が既定閾値
     * {@code MEMBER_PLUS} で一律 fail-closed になる欠陥）。本メソッドは既存の2 SQLに加えて
     * {@link UserRoleRepository#findMembershipRoleKindsForOrganizationDescendants} を追加で1回呼び、
     * 配下チーム所属のロール（MEMBER/SUPPORTER）を合成する。候補者数に依らず定数（SQL 3本）のまま。</p>
     *
     * @param userIds        候補ユーザー ID 集合
     * @param organizationId 組織 ID（母集団の根）
     * @return ユーザー ID → 実効ロール名（直接所属 ∪ 配下チーム所属の最強ロール）
     */
    public Map<Long, String> resolveEffectiveRoleNamesIncludingOrgDescendants(
            java.util.Collection<Long> userIds, Long organizationId) {
        Map<Long, String> roleByUser = resolveEffectiveRoleNames(userIds, organizationId, "ORGANIZATION");
        if (userIds == null || userIds.isEmpty() || organizationId == null) {
            return roleByUser;
        }
        List<UserRoleRepository.OrgDescendantMembershipRoleRow> descendantRoles =
                userRoleRepository.findMembershipRoleKindsForOrganizationDescendants(
                        organizationId, userIds, ORG_DESCENDANT_MAX_DEPTH);
        for (UserRoleRepository.OrgDescendantMembershipRoleRow row : descendantRoles) {
            if (row.getRoleKind() != null) {
                mergeStrongestRole(roleByUser, row.getUserId(), row.getRoleKind());
            }
        }
        return roleByUser;
    }

    /** priority が最小（＝最強）のロール名を採用する（{@link #resolveEffectiveRoleNames} 専用）。 */
    private void mergeStrongestRole(Map<Long, String> roleByUser, Long userId, String roleName) {
        if (userId == null || roleName == null) {
            return;
        }
        String current = roleByUser.get(userId);
        if (current == null
                || com.mannschaft.app.common.visibility.RolePriority.priority(roleName)
                        < com.mannschaft.app.common.visibility.RolePriority.priority(current)) {
            roleByUser.put(userId, roleName);
        }
    }

    /**
     * ユーザーが指定スコープの SUPPORTER かどうかを返す。
     *
     * <p>F00.5 Phase 5: memberships.role_kind = SUPPORTER で判定する（旧 user_roles 参照から切替）。
     * Phase 4 で user_roles から SUPPORTER 行が削除されているため、旧経路では常に false を返す問題があった。</p>
     *
     * @param userId    操作ユーザー
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param scopeType スコープ種別（"TEAM" または "ORGANIZATION"）
     * @return SUPPORTER の場合 true
     */
    public boolean isSupporter(Long userId, Long scopeId, String scopeType) {
        ScopeType scope = ScopeType.valueOf(scopeType);
        return membershipRepository.existsActiveByUserAndScopeAndRoleKind(userId, scope, scopeId, RoleKind.SUPPORTER);
    }

    /**
     * ユーザーがADMINまたはDEPUTY_ADMINかどうかを返す。
     */
    public boolean isAdminOrAbove(Long userId, Long scopeId, String scopeType) {
        String roleName = getRoleName(userId, scopeId, scopeType);
        return roleName != null && ADMIN_ROLES.contains(roleName);
    }

    /**
     * ユーザーがADMINかどうかを返す。
     */
    public boolean isAdmin(Long userId, Long scopeId, String scopeType) {
        String roleName = getRoleName(userId, scopeId, scopeType);
        return "ADMIN".equals(roleName);
    }

    /**
     * ユーザーが指定ロール以上（priority値がロール以下）かどうかを返す。
     * ロール優先度（{@code roles} テーブル）: SYSTEM_ADMIN(1) &gt; ADMIN(2) &gt; DEPUTY_ADMIN(3)
     * &gt; MEMBER(4) &gt; SUPPORTER(5) &gt; GUEST(6)。
     *
     * <p>F00.5 §8.3 根治: 有効ロールを {@link #resolveEffectiveRoleName} で解決するよう書き換えた。
     * これにより、user_roles から削除済みの MEMBER / SUPPORTER（memberships 専属）でも
     * {@code hasRoleOrAbove("MEMBER")} / {@code hasRoleOrAbove("SUPPORTER")} が正しく判定される。
     * ADMIN / DEPUTY_ADMIN の挙動は不変（priority 最強の user_roles ロールが採用されるため）。</p>
     */
    public boolean hasRoleOrAbove(Long userId, Long scopeId, String scopeType, String requiredRoleName) {
        return resolveEffectiveRole(userId, scopeId, scopeType)
                .map(effective -> {
                    RoleEntity requiredRole = roleRepository.findByName(requiredRoleName).orElse(null);
                    return requiredRole != null && effective.priority() <= requiredRole.getPriority();
                })
                .orElse(false);
    }

    /**
     * ADMIN/DEPUTY_ADMINであることを要求する。違反時は403。
     */
    public void checkAdminOrAbove(Long userId, Long scopeId, String scopeType) {
        if (!isAdminOrAbove(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ADMIN 以上 or 指定 Permission を持つ DEPUTY_ADMIN かを判定する（F18 Phase 4 第二陣 2B）。
     *
     * <p>挙動:</p>
     * <ul>
     *   <li>ADMIN は無条件で許可</li>
     *   <li>DEPUTY_ADMIN は次のいずれかを満たす場合のみ許可:
     *     <ul>
     *       <li>{@code role_permissions} に {@code is_default=1} で permission が登録されている</li>
     *       <li>{@code permission_groups} 経由で permission が個別付与されている</li>
     *     </ul>
     *   </li>
     *   <li>それ以外（MEMBER / SUPPORTER / 非メンバー / 天井登録のみの DEPUTY_ADMIN）は 403 COMMON_002</li>
     * </ul>
     *
     * <p>本メソッドは {@code ORGANIZATION} スコープ専用。{@code TEAM} スコープでの使用は現状未対応のため、
     * 渡された場合は {@link IllegalArgumentException} を投げる。</p>
     *
     * @param userId         操作者ユーザー ID
     * @param scopeId        組織 ID
     * @param scopeType      "ORGANIZATION" 固定
     * @param permissionName 必要な Permission 名（例: {@code "POINT_CARD_STAMP_ISSUE"}）
     * @throws BusinessException        権限なしの場合（COMMON_002）
     * @throws IllegalArgumentException scopeType が ORGANIZATION 以外の場合
     */
    public void checkAdminOrHasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        if (!"ORGANIZATION".equals(scopeType)) {
            throw new IllegalArgumentException(
                    "checkAdminOrHasPermission は ORGANIZATION スコープ専用です: " + scopeType);
        }
        // 1. ADMIN なら無条件許可
        if (isAdmin(userId, scopeId, scopeType)) {
            return;
        }
        // 2. DEPUTY_ADMIN かつ Permission 保有なら許可
        if (userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                userId, scopeId, permissionName)) {
            return;
        }
        // 3. それ以外は拒否
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    // ========================================
    // SYSTEM_ADMIN 判定
    // ========================================

    /**
     * ユーザーが SYSTEM_ADMIN かどうかを返す。
     */
    public boolean isSystemAdmin(Long userId) {
        return userRoleRepository.existsSystemAdminByUserId(userId) > 0;
    }

    /**
     * SYSTEM_ADMIN であることを要求する。違反時は403。
     */
    public void checkSystemAdmin(Long userId) {
        if (!isSystemAdmin(userId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 対象ユーザーがプラットフォームレベルの唯一の SYSTEM_ADMIN である場合、退会をブロックする。
     *
     * <p>本メソッドは auth ドメインの {@code UserService} がクロスドメイン参照なしに
     * ロール判定を行えるよう、共通ヘルパー ({@code common} パッケージ) に集約したものである。
     * {@code UserRoleRepository} は role ドメインに属するため、直接 auth から呼ぶと
     * ドメイン境界原則5（@Transactional はドメイン内に閉じる）に違反する。
     * {@code AccessControlService} を経由することでドメイン越境を解消している。</p>
     *
     * @param userId 退会対象ユーザーID
     * @throws BusinessException {@code GDPR_006}: 唯一の SYSTEM_ADMIN は退会不可
     */
    public void checkNotLastSystemAdmin(Long userId) {
        long systemAdminCount = userRoleRepository.countSystemAdmins();
        if (systemAdminCount <= 1 && userRoleRepository.isSystemAdmin(userId) > 0) {
            throw new BusinessException(GdprErrorCode.GDPR_006);
        }
    }

    // ========================================
    // 権限チェック
    // ========================================

    /**
     * ユーザーが特定の権限を持っていることを要求する。違反時は403。
     */
    public void checkPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        if (!hasPermission(userId, scopeId, scopeType, permissionName)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ユーザーが指定スコープで特定の権限を持っているかどうかを返す（boolean 版）。
     *
     * <p>{@link #checkPermission} の例外を投げない版。{@code @PreAuthorize} の SpEL から
     * 参照する {@code AccessGuard} 等、boolean を必要とする呼出元のために提供する。
     * 判定本体は {@code roleService.hasPermission} に委譲する（ロジックの二重化を避ける）。</p>
     *
     * @param userId         操作ユーザー
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param permissionName 必要な Permission 名
     * @return 権限を保有していれば true
     */
    public boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        return roleService.hasPermission(userId, scopeId, scopeType, permissionName);
    }

    // ========================================
    // 複合チェック（本人 or ADMIN）
    // ========================================

    /**
     * 本人またはADMIN/DEPUTY_ADMINであることを検証する。
     * コメント削除など「本人 or 管理者のみ」のパターンで使用。
     */
    public void checkOwnerOrAdmin(Long currentUserId, Long resourceOwnerId,
                                   Long scopeId, String scopeType) {
        if (currentUserId.equals(resourceOwnerId)) {
            return; // 本人はOK
        }
        if (!isAdminOrAbove(currentUserId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // ケアリンク検証
    // ========================================

    /**
     * 見守り者（保護者）がケア対象者への ACTIVE なケアリンクを持つか確認する。
     * リンクが存在しない場合は 403 をスローする。
     *
     * @param watcherUserId       見守り者（保護者）のユーザーID
     * @param careRecipientUserId ケア対象者（生徒）のユーザーID
     */
    public void checkCareLink(Long watcherUserId, Long careRecipientUserId) {
        boolean linked = userCareLinkRepository
                .existsByCareRecipientUserIdAndWatcherUserIdAndStatus(
                        careRecipientUserId, watcherUserId, CareLinkStatus.ACTIVE);
        if (!linked) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 指定スコープ種別でユーザーが現在 ACTIVE 所属している scopeId → joinedAt のマップを返す。
     * 所属一覧 API（MeController）が membership 由来の所属を列挙するための Service 窓口。
     * 他ドメインから membership.entity を直接参照させないための境界（D-1 クロスドメイン entity 依存の遮断）。
     */
    public Map<Long, LocalDateTime> findActiveMembershipJoinedAtByScope(Long userId, String scopeType) {
        ScopeType scope = ScopeType.valueOf(scopeType);
        Map<Long, LocalDateTime> result = new LinkedHashMap<>();
        for (MembershipEntity m : membershipRepository.findActiveByUserAndScopeType(userId, scope)) {
            result.putIfAbsent(m.getScopeId(), m.getJoinedAt());
        }
        return result;
    }

    /** 指定スコープの ACTIVE な distinct ユーザー数を返す（membership 基準）。 */
    public int countActiveDistinctMembers(String scopeType, Long scopeId) {
        return (int) membershipRepository.countActiveDistinctUsersByScope(ScopeType.valueOf(scopeType), scopeId);
    }

    /**
     * ユーザーが所属する指定スコープ種別の scopeId 群を「{@code user_roles} ∪ {@code memberships}」の
     * 和集合で列挙する（{@link com.mannschaft.app.role.controller.MeController} の所属列挙ロジックを
     * 共通窓口として集約したもの）。
     *
     * <p>F00.5 で MEMBER / SUPPORTER 所属が {@code user_roles} から {@code memberships} へ移管されたため、
     * 所属の列挙は両系統の和集合を取る必要がある（{@code memberships} 専属の所属が欠落する退行を防ぐ）。
     * 本メソッドは所属一覧 API（MeController）と同じ 2 系統を 1 箇所で UNION し、ストレージ使用量参照
     * （{@code GET /api/v1/me/storage/usage}）など「本人の所属スコープを列挙する」用途で再利用する。</p>
     *
     * <p>列挙順は安定させる（{@code user_roles} 由来を先、{@code memberships} 由来を後に追加した
     * {@link LinkedHashSet} の順）。本メソッドは両 Repository を<b>読むのみ・書かない</b>。</p>
     *
     * @param userId    操作ユーザー
     * @param scopeType スコープ種別（{@code "TEAM"} または {@code "ORGANIZATION"}）
     * @return 所属する scopeId の集合（重複なし・挿入順保持）。所属が無ければ空集合
     * @throws IllegalArgumentException scopeType が TEAM / ORGANIZATION 以外の場合
     */
    public Set<Long> findAffiliatedScopeIds(Long userId, String scopeType) {
        Set<Long> scopeIds = new java.util.LinkedHashSet<>();
        if ("TEAM".equals(scopeType)) {
            for (UserRoleEntity ur : userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId)) {
                scopeIds.add(ur.getTeamId());
            }
        } else if ("ORGANIZATION".equals(scopeType)) {
            for (UserRoleEntity ur : userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId)) {
                scopeIds.add(ur.getOrganizationId());
            }
        } else {
            throw new IllegalArgumentException(
                    "findAffiliatedScopeIds は TEAM / ORGANIZATION スコープ専用です: " + scopeType);
        }
        // memberships 由来の所属を和集合に追加する（既存の Service 窓口を再利用）。
        scopeIds.addAll(findActiveMembershipJoinedAtByScope(userId, scopeType).keySet());
        return scopeIds;
    }

    /**
     * ユーザーが ADMIN または DEPUTY_ADMIN として管理している指定スコープ種別の scopeId 群を列挙する
     * （ダッシュボード司令塔第二弾: 承認待ち横断集約の認可フィルタ）。
     *
     * <p>ADMIN/DEPUTY_ADMIN は {@code user_roles} テーブル由来のみで、{@code memberships.role_kind} は
     * MEMBER/SUPPORTER のみを保持する（§8.3 系統分離）。そのため本メソッドは {@code user_roles} を
     * スコープ種別ごとに 1 回だけクエリし（{@link #findAffiliatedScopeIds} と同じ
     * {@code findByUserIdAndTeamIdIsNotNull}/{@code findByUserIdAndOrganizationIdIsNotNull} を再利用）、
     * ADMIN/DEPUTY_ADMIN の roleId（呼び出し 1 回・スコープ数に依存しない定数回）でメモリ上フィルタする。
     * スコープ数 N に対し DB 往復は定数回で済み、N+1 を発生させない（司令塔第二弾 AC-B1-5）。</p>
     *
     * <p>本メソッドが返す scopeId は「ユーザーが当該スコープの ADMIN/DEPUTY_ADMIN である」ことのみを
     * 保証する一次フィルタである。個別ドメインの参照時は各 Facade/Service が
     * {@link #checkAdminOrAbove} 等で再検証する二重防御構成を前提とする。</p>
     *
     * @param userId    操作ユーザー
     * @param scopeType スコープ種別（{@code "TEAM"} または {@code "ORGANIZATION"}）
     * @return ADMIN/DEPUTY_ADMIN として所属する scopeId の集合（重複なし）。該当なしなら空集合
     * @throws IllegalArgumentException scopeType が TEAM / ORGANIZATION 以外の場合
     */
    public Set<Long> findAdminOrAboveScopeIds(Long userId, String scopeType) {
        Set<Long> adminRoleIds = new java.util.HashSet<>();
        roleRepository.findByName("ADMIN").ifPresent(r -> adminRoleIds.add(r.getId()));
        roleRepository.findByName("DEPUTY_ADMIN").ifPresent(r -> adminRoleIds.add(r.getId()));
        if (adminRoleIds.isEmpty()) {
            // roles マスタに ADMIN/DEPUTY_ADMIN が存在しない異常系（通常到達しない）。
            return Set.of();
        }

        Set<Long> scopeIds = new java.util.LinkedHashSet<>();
        if ("TEAM".equals(scopeType)) {
            for (UserRoleEntity ur : userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId)) {
                if (adminRoleIds.contains(ur.getRoleId())) {
                    scopeIds.add(ur.getTeamId());
                }
            }
        } else if ("ORGANIZATION".equals(scopeType)) {
            for (UserRoleEntity ur : userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId)) {
                if (adminRoleIds.contains(ur.getRoleId())) {
                    scopeIds.add(ur.getOrganizationId());
                }
            }
        } else {
            throw new IllegalArgumentException(
                    "findAdminOrAboveScopeIds は TEAM / ORGANIZATION スコープ専用です: " + scopeType);
        }
        return scopeIds;
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private Optional<UserRoleEntity> findUserRole(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.findByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId);
    }
}
