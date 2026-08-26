package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link PermissionGroupService} の単体テスト。
 * 権限グループのCRUD・ユーザー割当ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionGroupService 単体テスト")
class PermissionGroupServiceTest {

    @Mock
    private PermissionGroupRepository permissionGroupRepository;

    @Mock
    private PermissionGroupPermissionRepository permissionGroupPermissionRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserPermissionGroupRepository userPermissionGroupRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private PermissionGroupService permissionGroupService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCOPE_ID = 1L;
    private static final Long GROUP_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long CREATED_BY = 200L;
    private static final Long PERM_ID_1 = 301L;
    private static final Long PERM_ID_2 = 302L;

    private PermissionGroupEntity createGroupEntity(Long id, String name) {
        return PermissionGroupEntity.builder()
                .id(id)
                .teamId(SCOPE_ID)
                .name(name)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .createdBy(CREATED_BY)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private PermissionEntity createPermissionEntity(Long id, String name) {
        return PermissionEntity.builder()
                .id(id)
                .name(name)
                .displayName(name + " 表示名")
                .scope(PermissionEntity.Scope.TEAM)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private PermissionGroupRequest createRequest() {
        return new PermissionGroupRequest("管理者グループ", "DEPUTY_ADMIN", List.of(PERM_ID_1, PERM_ID_2));
    }

    // ========================================
    // createPermissionGroup
    // ========================================

    @Nested
    @DisplayName("createPermissionGroup")
    class CreatePermissionGroup {

        @Test
        @DisplayName("正常系: TEAMスコープで権限グループが作成される")
        void createPermissionGroup_TEAMスコープ_作成される() {
            // Given
            PermissionGroupRequest req = createRequest();
            List<PermissionEntity> permissions = List.of(
                    createPermissionEntity(PERM_ID_1, "MEMBER_MANAGE"),
                    createPermissionEntity(PERM_ID_2, "SCHEDULE_MANAGE")
            );
            given(permissionRepository.findByIdIn(req.getPermissionIds())).willReturn(permissions);
            given(permissionGroupRepository.save(any(PermissionGroupEntity.class)))
                    .willAnswer(invocation -> {
                        PermissionGroupEntity saved = invocation.getArgument(0);
                        // Set ID on the original entity via reflection since production code doesn't use the return value
                        try {
                            var idField = PermissionGroupEntity.class.getDeclaredField("id");
                            idField.setAccessible(true);
                            idField.set(saved, GROUP_ID);
                        } catch (Exception ignored) {}
                        return saved;
                    });
            given(permissionGroupPermissionRepository.save(any(PermissionGroupPermissionEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                    PermissionGroupPermissionEntity.builder().groupId(GROUP_ID).permissionId(PERM_ID_1).build()
            ));
            given(permissionRepository.findById(PERM_ID_1)).willReturn(Optional.of(createPermissionEntity(PERM_ID_1, "MEMBER_MANAGE")));

            // When
            ApiResponse<PermissionGroupResponse> response =
                    permissionGroupService.createPermissionGroup(SCOPE_ID, "TEAM", req, CREATED_BY);

            // Then
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getName()).isEqualTo("管理者グループ");
            verify(permissionGroupRepository).save(any(PermissionGroupEntity.class));
            verify(permissionGroupPermissionRepository, times(2)).save(any(PermissionGroupPermissionEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しないパーミッションIDでROLE_007例外")
        void createPermissionGroup_パーミッション不在_ROLE007例外() {
            // Given
            PermissionGroupRequest req = createRequest();
            given(permissionRepository.findByIdIn(req.getPermissionIds()))
                    .willReturn(List.of(createPermissionEntity(PERM_ID_1, "MEMBER_MANAGE"))); // 1個しか見つからない

            // When / Then
            assertThatThrownBy(() -> permissionGroupService.createPermissionGroup(SCOPE_ID, "TEAM", req, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_007"));
        }
    }

    // ========================================
    // updatePermissionGroup
    // ========================================

    @Nested
    @DisplayName("updatePermissionGroup")
    class UpdatePermissionGroup {

        @Test
        @DisplayName("正常系: 権限グループが更新される")
        void updatePermissionGroup_正常_更新される() {
            // Given
            PermissionGroupEntity existing = createGroupEntity(GROUP_ID, "旧グループ名");
            given(permissionGroupRepository.findById(GROUP_ID)).willReturn(Optional.of(existing));

            PermissionGroupRequest req = new PermissionGroupRequest("新グループ名", "MEMBER", List.of(PERM_ID_1));
            given(permissionRepository.findByIdIn(req.getPermissionIds()))
                    .willReturn(List.of(createPermissionEntity(PERM_ID_1, "VIEW")));
            given(permissionGroupRepository.save(any(PermissionGroupEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(permissionGroupPermissionRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                    PermissionGroupPermissionEntity.builder().groupId(GROUP_ID).permissionId(PERM_ID_1).build()
            ));
            given(permissionRepository.findById(PERM_ID_1)).willReturn(Optional.of(createPermissionEntity(PERM_ID_1, "VIEW")));

            // When
            ApiResponse<PermissionGroupResponse> response =
                    permissionGroupService.updatePermissionGroup(GROUP_ID, req, CREATED_BY);

            // Then
            assertThat(response.getData().getName()).isEqualTo("新グループ名");
            verify(permissionGroupPermissionRepository).deleteByGroupId(GROUP_ID);
        }

        @Test
        @DisplayName("異常系: 存在しないグループIDでROLE_006例外")
        void updatePermissionGroup_グループ不在_ROLE006例外() {
            // Given
            given(permissionGroupRepository.findById(GROUP_ID)).willReturn(Optional.empty());
            PermissionGroupRequest req = createRequest();

            // When / Then
            assertThatThrownBy(() -> permissionGroupService.updatePermissionGroup(GROUP_ID, req, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_006"));
        }
    }

    // ========================================
    // deletePermissionGroup
    // ========================================

    @Nested
    @DisplayName("deletePermissionGroup")
    class DeletePermissionGroup {

        @Test
        @DisplayName("正常系: 権限グループが削除される")
        void deletePermissionGroup_正常_削除される() {
            // Given
            PermissionGroupEntity existing = createGroupEntity(GROUP_ID, "削除対象");
            given(permissionGroupRepository.findById(GROUP_ID)).willReturn(Optional.of(existing));

            // When
            permissionGroupService.deletePermissionGroup(GROUP_ID, CREATED_BY);

            // Then
            verify(permissionGroupRepository).delete(existing);
        }

        @Test
        @DisplayName("異常系: 存在しないグループでROLE_006例外")
        void deletePermissionGroup_グループ不在_ROLE006例外() {
            // Given
            given(permissionGroupRepository.findById(GROUP_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> permissionGroupService.deletePermissionGroup(GROUP_ID, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_006"));
        }
    }

    // ========================================
    // getPermissionGroups
    // ========================================

    @Nested
    @DisplayName("getPermissionGroups")
    class GetPermissionGroups {

        @Test
        @DisplayName("正常系: TEAMスコープのグループ一覧が返る")
        void getPermissionGroups_TEAMスコープ_一覧が返る() {
            // Given
            PermissionGroupEntity group = createGroupEntity(GROUP_ID, "グループA");
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of());

            // When
            List<PermissionGroupResponse> result = permissionGroupService.getPermissionGroups(SCOPE_ID, "TEAM");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("グループA");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープで検索される")
        void getPermissionGroups_ORGANIZATIONスコープ_検索される() {
            // Given
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            // When
            List<PermissionGroupResponse> result = permissionGroupService.getPermissionGroups(SCOPE_ID, "ORGANIZATION");

            // Then
            assertThat(result).isEmpty();
            verify(permissionGroupRepository).findByOrganizationId(SCOPE_ID);
        }
    }

    // ========================================
    // assignUserPermissionGroups
    // ========================================

    @Nested
    @DisplayName("assignUserPermissionGroups")
    class AssignUserPermissionGroups {

        @Test
        @DisplayName("正常系: ユーザーに権限グループが割り当てられる")
        void assignUserPermissionGroups_正常_割り当てられる() {
            // Given
            PermissionGroupEntity group = createGroupEntity(GROUP_ID, "グループA");
            // Issue #2797: 付与の関門は findById（存在確認）から findByScope（スコープ内集合）へ移った。
            // findById のスタブは不要になったため削除（アサーションは不変）。
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
            given(userPermissionGroupRepository.save(any(UserPermissionGroupEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(GROUP_ID));

            // When
            permissionGroupService.assignUserPermissionGroups(USER_ID, SCOPE_ID, "TEAM", req, CREATED_BY);

            // Then
            verify(userPermissionGroupRepository).deleteByUserIdAndGroupIdIn(USER_ID, List.of(GROUP_ID));
            verify(userPermissionGroupRepository).save(any(UserPermissionGroupEntity.class));
        }

        @Test
        @DisplayName("異常系: スコープ内に存在しないグループIDでROLE_006例外")
        void assignUserPermissionGroups_グループ不在_ROLE006例外() {
            // Given
            // Issue #2797: 不在の ID と他スコープの ID はいずれも「スコープ内集合に無い」として
            // 同一の ROLE_006（404）へ畳まれる（存在秘匿）。findById のスタブは不要になった。
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of());

            UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(GROUP_ID));

            // When / Then
            assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                    USER_ID, SCOPE_ID, "TEAM", req, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_006"));
        }
    }

    // ========================================
    // hasPermission（F09.13 Phase 2-α-3 で追加）
    // ========================================

    @Nested
    @DisplayName("hasPermission")
    class HasPermission {

        private static final String PERM_NAME = "PROPERTY_HISTORY_MANAGE";

        @Test
        @DisplayName("正常系: ユーザーが権限グループ経由で当該パーミッションを保有 → true")
        void hasPermission_保有あり_true() {
            // Given: scope 内に group が存在し、ユーザー割当もあり、その group に PERM_NAME が含まれる
            PermissionGroupEntity group = createGroupEntity(GROUP_ID, "管理者グループ");
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));

            UserPermissionGroupEntity assignment = UserPermissionGroupEntity.builder()
                    .userId(USER_ID).groupId(GROUP_ID).build();
            given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(assignment));

            PermissionGroupPermissionEntity pgp = PermissionGroupPermissionEntity.builder()
                    .groupId(GROUP_ID).permissionId(PERM_ID_1).build();
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(pgp));

            given(permissionRepository.findById(PERM_ID_1))
                    .willReturn(Optional.of(createPermissionEntity(PERM_ID_1, PERM_NAME)));

            // When
            boolean result = permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: ユーザーは group に割当られているが当該 permission を持たない → false")
        void hasPermission_別パーミッションのみ_false() {
            // Given
            PermissionGroupEntity group = createGroupEntity(GROUP_ID, "閲覧者グループ");
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));

            UserPermissionGroupEntity assignment = UserPermissionGroupEntity.builder()
                    .userId(USER_ID).groupId(GROUP_ID).build();
            given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(assignment));

            PermissionGroupPermissionEntity pgp = PermissionGroupPermissionEntity.builder()
                    .groupId(GROUP_ID).permissionId(PERM_ID_2).build();
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(pgp));

            given(permissionRepository.findById(PERM_ID_2))
                    .willReturn(Optional.of(createPermissionEntity(PERM_ID_2, "PROPERTY_HISTORY_VIEW")));

            // When
            boolean result = permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME);

            // Then: VIEW のみで MANAGE は持たない → false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("異常系: scope 内に当該ユーザーの group 割当が皆無 → false（早期 return）")
        void hasPermission_ユーザー未割当_false() {
            // Given: scope に group は存在するが、ユーザーには別 scope の group しか割当られていない
            PermissionGroupEntity group = createGroupEntity(GROUP_ID, "管理者グループ");
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));

            UserPermissionGroupEntity otherAssignment = UserPermissionGroupEntity.builder()
                    .userId(USER_ID).groupId(999L).build(); // 当該 scope 外の group ID
            given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(otherAssignment));

            // When
            boolean result = permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME);

            // Then
            assertThat(result).isFalse();
            // 早期 return により permissionGroupPermissionRepository は呼ばれない
            verify(permissionGroupPermissionRepository, never()).findByGroupId(anyLong());
        }

        @Test
        @DisplayName("異常系: scope に group が皆無 → false（早期 return）")
        void hasPermission_scope内グループ皆無_false() {
            // Given
            given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of());

            // When
            boolean result = permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME);

            // Then
            assertThat(result).isFalse();
            // 早期 return により後続の repository は呼ばれない
            verify(userPermissionGroupRepository, never()).findByUserId(anyLong());
        }

        @Test
        @DisplayName("異常系: userId / scopeType / scopeId / permissionName のいずれかが null → false")
        void hasPermission_引数null_false() {
            assertThat(permissionGroupService.hasPermission(null, "TEAM", SCOPE_ID, PERM_NAME)).isFalse();
            assertThat(permissionGroupService.hasPermission(USER_ID, null, SCOPE_ID, PERM_NAME)).isFalse();
            assertThat(permissionGroupService.hasPermission(USER_ID, "TEAM", null, PERM_NAME)).isFalse();
            assertThat(permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, null)).isFalse();
            // null 引数では一切 repository 呼出ししない
            verifyNoInteractions(permissionGroupRepository);
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープでも判定できる")
        void hasPermission_ORGANIZATION_正常() {
            // Given
            PermissionGroupEntity group = PermissionGroupEntity.builder()
                    .id(GROUP_ID).organizationId(SCOPE_ID).name("組織管理者")
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                    .createdBy(CREATED_BY).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of(group));

            UserPermissionGroupEntity assignment = UserPermissionGroupEntity.builder()
                    .userId(USER_ID).groupId(GROUP_ID).build();
            given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(assignment));

            PermissionGroupPermissionEntity pgp = PermissionGroupPermissionEntity.builder()
                    .groupId(GROUP_ID).permissionId(PERM_ID_1).build();
            given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(pgp));

            given(permissionRepository.findById(PERM_ID_1))
                    .willReturn(Optional.of(createPermissionEntity(PERM_ID_1, PERM_NAME)));

            // When
            boolean result = permissionGroupService.hasPermission(USER_ID, "ORGANIZATION", SCOPE_ID, PERM_NAME);

            // Then
            assertThat(result).isTrue();
            verify(permissionGroupRepository).findByOrganizationId(SCOPE_ID);
        }
    }
}
