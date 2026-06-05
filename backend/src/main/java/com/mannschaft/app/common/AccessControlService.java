package com.mannschaft.app.common;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

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
