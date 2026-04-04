package com.mannschaft.app.common;

import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     */
    public boolean isMember(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.existsByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.existsByUserIdAndOrganizationId(userId, scopeId);
    }

    // ========================================
    // ロール判定
    // ========================================

    /**
     * ユーザーのスコープ内ロール名を取得する。メンバーでない場合はnull。
     */
    public String getRoleName(Long userId, Long scopeId, String scopeType) {
        return findUserRole(userId, scopeId, scopeType)
                .flatMap(ur -> roleRepository.findById(ur.getRoleId()))
                .map(RoleEntity::getName)
                .orElse(null);
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
     * ロール優先度: ADMIN(1) > DEPUTY_ADMIN(2) > MEMBER(3) > SUPPORTER(4) > GUEST(5)
     */
    public boolean hasRoleOrAbove(Long userId, Long scopeId, String scopeType, String requiredRoleName) {
        return findUserRole(userId, scopeId, scopeType)
                .flatMap(ur -> roleRepository.findById(ur.getRoleId()))
                .map(userRole -> {
                    RoleEntity requiredRole = roleRepository.findByName(requiredRoleName).orElse(null);
                    return requiredRole != null && userRole.getPriority() <= requiredRole.getPriority();
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

    // ========================================
    // 権限チェック
    // ========================================

    /**
     * ユーザーが特定の権限を持っていることを要求する。違反時は403。
     */
    public void checkPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        if (!roleService.hasPermission(userId, scopeId, scopeType, permissionName)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
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
    // ヘルパー（private）
    // ========================================

    private Optional<UserRoleEntity> findUserRole(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.findByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId);
    }
}
