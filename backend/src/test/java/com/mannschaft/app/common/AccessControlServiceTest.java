package com.mannschaft.app.common;

import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AccessControlService} の単体テスト。
 * メンバーシップ検証・ロール判定・権限チェック・複合チェックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessControlService 単体テスト")
class AccessControlServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private AccessControlService accessControlService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ROLE_ID = 100L;

    private UserRoleEntity createUserRole(Long roleId) {
        return UserRoleEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .roleId(roleId)
                .teamId(SCOPE_ID)
                .build();
    }

    private RoleEntity createRole(String name, int priority) {
        return RoleEntity.builder()
                .id(ROLE_ID)
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem(true)
                .build();
    }

    // ========================================
    // checkMembership
    // ========================================

    @Nested
    @DisplayName("checkMembership")
    class CheckMembership {

        @Test
        @DisplayName("正常系: TEAMスコープでメンバーの場合は例外なし")
        void checkMembership_TEAMスコープでメンバー_例外なし() {
            // Given
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(true);

            // When / Then（例外が発生しないことを確認）
            accessControlService.checkMembership(USER_ID, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでメンバーの場合は例外なし")
        void checkMembership_ORGANIZATIONスコープでメンバー_例外なし() {
            // Given
            given(userRoleRepository.existsByUserIdAndOrganizationId(USER_ID, SCOPE_ID)).willReturn(true);

            // When / Then
            accessControlService.checkMembership(USER_ID, SCOPE_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("異常系: 非メンバーでCOMMON_002例外")
        void checkMembership_非メンバー_COMMON002例外() {
            // Given
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkMembership(USER_ID, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // isMember
    // ========================================

    @Nested
    @DisplayName("isMember")
    class IsMember {

        @Test
        @DisplayName("正常系: TEAMスコープでメンバーならtrue")
        void isMember_TEAMスコープでメンバー_true() {
            // Given
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(true);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでメンバーならtrue")
        void isMember_ORGANIZATIONスコープでメンバー_true() {
            // Given
            given(userRoleRepository.existsByUserIdAndOrganizationId(USER_ID, SCOPE_ID)).willReturn(true);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "ORGANIZATION");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: 非メンバーならfalse")
        void isMember_非メンバー_false() {
            // Given
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(false);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // getRoleName
    // ========================================

    @Nested
    @DisplayName("getRoleName")
    class GetRoleName {

        @Test
        @DisplayName("正常系: TEAMスコープでロール名が返る")
        void getRoleName_TEAMスコープ_ロール名が返る() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(roleName).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでロール名が返る")
        void getRoleName_ORGANIZATIONスコープ_ロール名が返る() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "ORGANIZATION");

            // Then
            assertThat(roleName).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("正常系: メンバーでない場合はnull")
        void getRoleName_非メンバー_null() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(roleName).isNull();
        }
    }

    // ========================================
    // isAdminOrAbove
    // ========================================

    @Nested
    @DisplayName("isAdminOrAbove")
    class IsAdminOrAbove {

        @Test
        @DisplayName("正常系: ADMINロールでtrue")
        void isAdminOrAbove_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: DEPUTY_ADMINロールでtrue")
        void isAdminOrAbove_DEPUTY_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: MEMBERロールでfalse")
        void isAdminOrAbove_MEMBERロール_false() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("正常系: 非メンバーでfalse")
        void isAdminOrAbove_非メンバー_false() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // isAdmin
    // ========================================

    @Nested
    @DisplayName("isAdmin")
    class IsAdmin {

        @Test
        @DisplayName("正常系: ADMINロールでtrue")
        void isAdmin_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: DEPUTY_ADMINロールでfalse")
        void isAdmin_DEPUTY_ADMINロール_false() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // hasRoleOrAbove
    // ========================================

    @Nested
    @DisplayName("hasRoleOrAbove")
    class HasRoleOrAbove {

        @Test
        @DisplayName("正常系: ADMINがMEMBER以上を満たすのでtrue")
        void hasRoleOrAbove_ADMIN対MEMBER_true() {
            // Given
            Long adminRoleId = 1L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(adminRoleId).teamId(SCOPE_ID).build();
            RoleEntity adminRole = RoleEntity.builder()
                    .id(adminRoleId).name("ADMIN").displayName("管理者").priority(1).isSystem(true).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(3L).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(adminRoleId)).willReturn(Optional.of(adminRole));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(memberRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: MEMBERがADMIN以上を満たさないのでfalse")
        void hasRoleOrAbove_MEMBER対ADMIN_false() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();
            RoleEntity adminRole = RoleEntity.builder()
                    .id(1L).name("ADMIN").displayName("管理者").priority(1).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "ADMIN");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("境界値: 同一ロール同士でtrue")
        void hasRoleOrAbove_同一ロール_true() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(memberRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: 非メンバーでfalse")
        void hasRoleOrAbove_非メンバー_false() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("境界値: 要求ロールが存在しない場合はfalse")
        void hasRoleOrAbove_要求ロール不在_false() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("NONEXISTENT")).willReturn(Optional.empty());

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "NONEXISTENT");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // checkAdminOrAbove
    // ========================================

    @Nested
    @DisplayName("checkAdminOrAbove")
    class CheckAdminOrAbove {

        @Test
        @DisplayName("正常系: ADMINロールで例外なし")
        void checkAdminOrAbove_ADMINロール_例外なし() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            accessControlService.checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: MEMBERロールでCOMMON_002例外")
        void checkAdminOrAbove_MEMBERロール_COMMON002例外() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // checkPermission
    // ========================================

    @Nested
    @DisplayName("checkPermission")
    class CheckPermission {

        @Test
        @DisplayName("正常系: 権限ありで例外なし")
        void checkPermission_権限あり_例外なし() {
            // Given
            given(roleService.hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE")).willReturn(true);

            // When / Then
            accessControlService.checkPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE");
            verify(roleService).hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE");
        }

        @Test
        @DisplayName("異常系: 権限なしでCOMMON_002例外")
        void checkPermission_権限なし_COMMON002例外() {
            // Given
            given(roleService.hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // checkOwnerOrAdmin
    // ========================================

    @Nested
    @DisplayName("checkOwnerOrAdmin")
    class CheckOwnerOrAdmin {

        @Test
        @DisplayName("正常系: 本人の場合は例外なし")
        void checkOwnerOrAdmin_本人_例外なし() {
            // Given
            Long resourceOwnerId = USER_ID;

            // When / Then（本人なのでロール判定は呼ばれない）
            accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: 他人だがADMINの場合は例外なし")
        void checkOwnerOrAdmin_他人だがADMIN_例外なし() {
            // Given
            Long resourceOwnerId = 999L;
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: 他人かつ非ADMINでCOMMON_002例外")
        void checkOwnerOrAdmin_他人かつ非ADMIN_COMMON002例外() {
            // Given
            Long resourceOwnerId = 999L;
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }
}
