package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;

    /**
     * ロール変更で外れる割当の中に ADMIN 専用権限（{@link PermissionGroupService#ADMIN_ONLY_GRANTABLE_PERMISSIONS}）を
     * 含むグループがある場合、操作者が当該スコープの ADMIN でなければ拒否する（PermissionGroupService の
     * ADMIN 専用判定と同じ {@code checkScopeAdminOnly}）。剥奪は権限グループ割当の解除と等価なため、
     * この経路で ADMIN 専用判定を迂回させない。削除（{@link #removeMismatched}）より前＝副作用前に呼ぶこと。
     */
    public void requireAdminIfAdminOnlyAssignmentsWouldBeRemoved(Long userId, Long scopeId, String scopeType,
                                                                  String effectiveRoleName, Long actorUserId) {
        List<Long> removedGroupIds = mismatchedGroupIds(scopeId, scopeType, effectiveRoleName);
        if (removedGroupIds.isEmpty()) {
            return;
        }
        List<Long> assigned = userPermissionGroupRepository.findByUserId(userId).stream()
                .map(UserPermissionGroupEntity::getGroupId)
                .filter(removedGroupIds::contains)
                .toList();
        if (assigned.isEmpty()) {
            return;
        }
        List<Long> permissionIds = assigned.stream()
                .flatMap(id -> permissionGroupPermissionRepository.findByGroupId(id).stream())
                .map(PermissionGroupPermissionEntity::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return;
        }
        boolean adminOnly = permissionRepository.findByIdIn(permissionIds).stream()
                .map(PermissionEntity::getName)
                .anyMatch(PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS::contains);
        if (adminOnly) {
            accessControlService.checkScopeAdminOnly(actorUserId, scopeId, scopeType);
        }
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
