package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** ADMIN を変更する書込み経路のロック順序を users → roles.ADMIN → user_roles に固定する。 */
@Service
@RequiredArgsConstructor
public class AdminRoleMutationLockService {

    private final UserRowLockService userRowLockService;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public List<Long> lockScopeAdminRows(Long scopeId, String scopeType, Long... userIds) {
        userRowLockService.lockAll(userIds);
        return lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);
    }

    /** 呼出元が対象 users を昇順ロック済みの場合に、ADMIN定義行とscope内ADMIN行をロックする。 */
    public List<Long> lockScopeAdminRowsAfterUsersLocked(Long scopeId, String scopeType) {
        return lockAdminRole().map(admin -> {
            if ("TEAM".equals(scopeType)) {
                return userRoleRepository.lockAdminUserIdsByTeamId(scopeId, admin.getId());
            }
            if ("ORGANIZATION".equals(scopeType)) {
                return userRoleRepository.lockAdminUserIdsByOrganizationId(scopeId, admin.getId());
            }
            return List.<Long>of();
        }).orElseGet(List::of);
    }

    /** 作成者 user を先にロックしてから ADMIN 定義行をロックする。 */
    public Optional<Long> lockAdminRoleIdForCreation(Long creatorUserId) {
        userRowLockService.lockAll(creatorUserId);
        return lockAdminRole().map(RoleEntity::getId);
    }

    private Optional<RoleEntity> lockAdminRole() {
        return roleRepository.findByNameForUpdate("ADMIN");
    }
}
