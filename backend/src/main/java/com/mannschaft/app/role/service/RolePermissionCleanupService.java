package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** ロール変更・離脱と同一トランザクションで不適格なpermission group割当を除去する。 */
@Service
@RequiredArgsConstructor
public class RolePermissionCleanupService {

    private final PermissionGroupRepository permissionGroupRepository;
    private final UserPermissionGroupRepository userPermissionGroupRepository;
    private final PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    private final PermissionRepository permissionRepository;

    /**
     * ロール変更で外れる割当（{@link #removeMismatched} が削除するもの）の中に、ADMIN 専用権限
     * （{@link PermissionGroupService#ADMIN_ONLY_GRANTABLE_PERMISSIONS}）を含むグループがあるかを返す。
     * 判定結果の扱い（ADMIN 以外の拒否）は呼び出し側（RoleService）が担う。削除より前に呼ぶこと。
     * 本クラスは AccessControlService（→ RoleService に依存）を持たない。依存の向きを保つため。
     */
    public boolean wouldRemoveAdminOnlyAssignments(Long userId, Long scopeId, String scopeType,
                                                    String effectiveRoleName) {
        List<Long> removedGroupIds = mismatchedGroupIds(scopeId, scopeType, effectiveRoleName);
        if (removedGroupIds.isEmpty()) {
            return false;
        }
        List<Long> assigned = userPermissionGroupRepository.findByUserId(userId).stream()
                .map(UserPermissionGroupEntity::getGroupId)
                .filter(removedGroupIds::contains)
                .toList();
        if (assigned.isEmpty()) {
            return false;
        }
        List<Long> permissionIds = assigned.stream()
                .flatMap(id -> permissionGroupPermissionRepository.findByGroupId(id).stream())
                .map(PermissionGroupPermissionEntity::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return false;
        }
        return permissionRepository.findByIdIn(permissionIds).stream()
                .map(PermissionEntity::getName)
                .anyMatch(PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS::contains);
    }

    private List<Long> mismatchedGroupIds(Long scopeId, String scopeType, String effectiveRoleName) {
        return ("TEAM".equals(scopeType)
                ? permissionGroupRepository.findByTeamId(scopeId)
                : permissionGroupRepository.findByOrganizationId(scopeId)).stream()
                .filter(group -> effectiveRoleName == null || group.getTargetRole() == null
                        || !effectiveRoleName.equals(group.getTargetRole().name()))
                .map(PermissionGroupEntity::getId)
                .toList();
    }

    @Transactional
    public void removeMismatched(Long userId, Long scopeId, String scopeType, String effectiveRoleName) {
        List<Long> groupIds = mismatchedGroupIds(scopeId, scopeType, effectiveRoleName);
        if (!groupIds.isEmpty()) {
            userPermissionGroupRepository.deleteByUserIdAndGroupIdIn(userId, groupIds);
        }
    }
}
