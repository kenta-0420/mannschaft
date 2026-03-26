package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link RoleService} の単体テスト。
 * ロール割当・変更・除名・退会・有効権限解決・オーナー譲渡を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleService 単体テスト")
class RoleServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ADMIN_ROLE_ID = 100L;
    private static final Long MEMBER_ROLE_ID = 101L;

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private PermissionGroupRepository permissionGroupRepository;
    @Mock private PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    @Mock private UserPermissionGroupRepository userPermissionGroupRepository;

    @InjectMocks
    private RoleService roleService;

    // ========================================
    // assignRole
    // ========================================

    @Nested
    @DisplayName("assignRole")
    class AssignRole {

        @Test
        @DisplayName("正常割当_ロールが保存される")
        void 正常割当_ロールが保存される() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("既存ロール上書き_旧ロール削除後に新ロール保存")
        void 既存ロール上書き_旧ロール削除後に新ロール保存() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity existing = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(existing));

            roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).delete(existing);
            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("存在しないロール_ROLE_001例外")
        void 存在しないロール_ROLE_001例外() {
            given(roleRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, 999L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        @Test
        @DisplayName("チームスコープ_teamIdにセットされる")
        void チームスコープ_teamIdにセットされる() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            roleService.assignRole(SCOPE_ID, "TEAM", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }
    }

    // ========================================
    // changeRole
    // ========================================

    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("正常変更_ロールが変更される")
        void 正常変更_ロールが変更される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));

            roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(ADMIN_ROLE_ID), USER_ID);

            verify(userRoleRepository).delete(current);
            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("最後のADMIN変更_ROLE_004例外")
        void 最後のADMIN変更_ROLE_004例外() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.countByOrganizationIdAndRoleId(SCOPE_ID, ADMIN_ROLE_ID)).willReturn(1L);

            assertThatThrownBy(() -> roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(MEMBER_ROLE_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_004"));
        }
    }

    // ========================================
    // removeMember
    // ========================================

    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("正常除名_ユーザーロールが削除される")
        void 正常除名_ユーザーロールが削除される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID);

            verify(userRoleRepository).delete(current);
        }

        @Test
        @DisplayName("最後のADMIN除名_ROLE_004例外")
        void 最後のADMIN除名_ROLE_004例外() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.countByOrganizationIdAndRoleId(SCOPE_ID, ADMIN_ROLE_ID)).willReturn(1L);

            assertThatThrownBy(() -> roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_004"));
        }
    }

    // ========================================
    // leaveScope
    // ========================================

    @Nested
    @DisplayName("leaveScope")
    class LeaveScope {

        @Test
        @DisplayName("正常退会_ユーザーロールが削除される")
        void 正常退会_ユーザーロールが削除される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.leaveScope(USER_ID, SCOPE_ID, "ORGANIZATION");

            verify(userRoleRepository).delete(current);
        }
    }

    // ========================================
    // resolveEffectivePermissions
    // ========================================

    @Nested
    @DisplayName("resolveEffectivePermissions")
    class ResolveEffectivePermissions {

        @Test
        @DisplayName("ロール由来と権限グループ由来が統合される")
        void ロール由来と権限グループ由来が統合される() {
            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(ur));

            RolePermissionEntity rp = RolePermissionEntity.builder()
                    .id(1L).roleId(ADMIN_ROLE_ID).permissionId(1L).isDefault(true).build();
            given(rolePermissionRepository.findByRoleId(ADMIN_ROLE_ID)).willReturn(List.of(rp));

            PermissionEntity perm = PermissionEntity.builder()
                    .id(1L).name("MEMBER_MANAGE").displayName("メンバー管理")
                    .scope(PermissionEntity.Scope.ORGANIZATION).build();
            given(permissionRepository.findById(1L)).willReturn(Optional.of(perm));

            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            List<String> permissions = roleService.resolveEffectivePermissions(USER_ID, SCOPE_ID, "ORGANIZATION");

            assertThat(permissions).contains("MEMBER_MANAGE");
        }

        @Test
        @DisplayName("ロール未割当_空リストが返される")
        void ロール未割当_空リストが返される() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            List<String> permissions = roleService.resolveEffectivePermissions(USER_ID, SCOPE_ID, "ORGANIZATION");

            assertThat(permissions).isEmpty();
        }
    }

    // ========================================
    // hasPermission
    // ========================================

    @Nested
    @DisplayName("hasPermission")
    class HasPermission {

        @Test
        @DisplayName("権限あり_trueが返される")
        void 権限あり_trueが返される() {
            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(ur));

            RolePermissionEntity rp = RolePermissionEntity.builder()
                    .id(1L).roleId(ADMIN_ROLE_ID).permissionId(1L).isDefault(true).build();
            given(rolePermissionRepository.findByRoleId(ADMIN_ROLE_ID)).willReturn(List.of(rp));

            PermissionEntity perm = PermissionEntity.builder()
                    .id(1L).name("MEMBER_MANAGE").displayName("メンバー管理")
                    .scope(PermissionEntity.Scope.ORGANIZATION).build();
            given(permissionRepository.findById(1L)).willReturn(Optional.of(perm));
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            boolean result = roleService.hasPermission(USER_ID, SCOPE_ID, "ORGANIZATION", "MEMBER_MANAGE");

            assertThat(result).isTrue();
        }
    }

    // ========================================
    // transferOwnership
    // ========================================

    @Nested
    @DisplayName("transferOwnership")
    class TransferOwnership {

        @Test
        @DisplayName("正常譲渡_ADMINとMEMBERが入れ替わる")
        void 正常譲渡_ADMINとMEMBERが入れ替わる() {
            UserRoleEntity currentUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            UserRoleEntity targetUserRole = UserRoleEntity.builder()
                    .id(2L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();

            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(currentUserRole));
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(targetUserRole));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(createMemberRole()));

            roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, TARGET_USER_ID);

            verify(userRoleRepository).delete(targetUserRole);
            verify(userRoleRepository).delete(currentUserRole);
        }

        @Test
        @DisplayName("自分自身への譲渡_ROLE_001例外")
        void 自分自身への譲渡_ROLE_001例外() {
            assertThatThrownBy(() -> roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        @Test
        @DisplayName("非ADMIN_ROLE_001例外")
        void 非ADMIN_ROLE_001例外() {
            UserRoleEntity currentUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();

            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(currentUserRole));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            assertThatThrownBy(() -> roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RoleEntity createAdminRole() {
        return RoleEntity.builder()
                .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
    }

    private RoleEntity createMemberRole() {
        return RoleEntity.builder()
                .id(MEMBER_ROLE_ID).name("MEMBER").displayName("メンバー").priority(4).isSystem(true).build();
    }
}
