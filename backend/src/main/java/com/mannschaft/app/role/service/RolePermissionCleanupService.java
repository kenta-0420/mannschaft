package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionGroupEntity;
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

    @Transactional
    public void removeMismatched(Long userId, Long scopeId, String scopeType, String effectiveRoleName) {
        List<Long> groupIds = ("TEAM".equals(scopeType)
                ? permissionGroupRepository.findByTeamId(scopeId)
                : permissionGroupRepository.findByOrganizationId(scopeId)).stream()
                .filter(group -> effectiveRoleName == null || group.getTargetRole() == null
                        || !effectiveRoleName.equals(group.getTargetRole().name()))
                .map(PermissionGroupEntity::getId)
                .toList();
        if (!groupIds.isEmpty()) {
            userPermissionGroupRepository.deleteByUserIdAndGroupIdIn(userId, groupIds);
        }
    }
}
