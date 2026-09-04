package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
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

    /**
     * 指定ユーザーへ ADMIN 役割（{@code user_roles} 行）を付与する。
     *
     * <p>D-1/D-5（クロスドメイン Entity/Repository 参照禁止）に従い、他ドメインへ
     * {@link UserRoleEntity}/{@link UserRoleRepository} を漏らさず、この窓口経由で付与する
     * （柱②-2 販促プロビジョニング招待承諾用。{@code OrganizationService#createOrganization} /
     * {@code TeamService#createTeam} と同型のロール付与）。
     * scopeType は teamId/organizationId のどちらか一方のみ非null であることを呼び出し元が保証する。</p>
     *
     * @param userId         付与対象ユーザー ID
     * @param roleId         付与するロール ID（{@link #lockAdminRoleIdForCreation} で取得した ADMIN の ID）
     * @param teamId         チームスコープの場合の team ID（組織スコープなら null）
     * @param organizationId 組織スコープの場合の organization ID（チームスコープなら null）
     */
    public void grantAdminRole(Long userId, Long roleId, Long teamId, Long organizationId) {
        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .teamId(teamId)
                .organizationId(organizationId)
                .build();
        userRoleRepository.save(userRole);
    }

    private Optional<RoleEntity> lockAdminRole() {
        return roleRepository.findByNameForUpdate("ADMIN");
    }
}
