package com.mannschaft.app.role.service;

import com.mannschaft.app.role.security.BillingPermissionGroupGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.auth.service.UserRowLockService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    @Mock
    private CacheManager cacheManager;

    @Mock
    private CacheErrorHandler cacheErrorHandler;

    @Mock
    private UserRowLockService userRowLockService;

    @Mock
    private BillingPermissionGroupGuard billingPermissionGroupGuard;

    @InjectMocks
    private PermissionGroupService permissionGroupService;

    @BeforeEach
    void stubPessimisticGroupLocks() {
        lenient().when(userRowLockService.lock(anyLong()))
                .thenReturn(UserRowLockService.UserState.ACTIVE);
        lenient().when(userRowLockService.lockAll(anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    Long[] userIds = new Long[] {
                            invocation.getArgument(0, Long.class),
                            invocation.getArgument(1, Long.class)
                    };
                    Map<Long, UserRowLockService.UserState> states = new LinkedHashMap<>();
                    for (Long userId : userIds) {
                        states.put(userId, UserRowLockService.UserState.ACTIVE);
                    }
                    return states;
                });
        lenient().when(accessControlService.isMember(anyLong(), anyLong(), anyString())).thenReturn(true);
        lenient().when(accessControlService.resolveEffectiveRoleName(anyLong(), anyLong(), anyString()))
                .thenReturn("DEPUTY_ADMIN");
        lenient().when(permissionGroupRepository.findByIdInForUpdateOrderByIdAsc(anyList()))
                .thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0)).stream()
                        .map(id -> createGroupEntity(id, "locked-" + id)).toList());
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCOPE_ID = 1L;
    private static final Long GROUP_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long CREATED_BY = 200L;
    private static final Long PERM_ID_1 = 301L;
    private static final Long PERM_ID_2 = 302L;
    private static final Long USER_ID_2 = 101L;
    private static final Long NORMAL_PERM_ID = 303L;
    private static final Long SENSITIVE_PERM_ID = 304L;

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
            given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(existing));

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
            given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.empty());
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
            given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(existing));

            // When
            permissionGroupService.deletePermissionGroup(GROUP_ID, CREATED_BY);

            // Then
            verify(permissionGroupRepository).delete(existing);
        }

        @Test
        @DisplayName("異常系: 存在しないグループでROLE_006例外")
        void deletePermissionGroup_グループ不在_ROLE006例外() {
            // Given
            given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.empty());

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
            InOrder lockOrder = inOrder(userRowLockService, permissionGroupRepository);
            lockOrder.verify(userRowLockService).lockAll(CREATED_BY, USER_ID);
            lockOrder.verify(permissionGroupRepository).findByTeamId(SCOPE_ID);
            verify(permissionGroupRepository)
                    .findByIdInForUpdateOrderByIdAsc(List.of(GROUP_ID));
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

    @Test
    @DisplayName("F09.14: 既存の敏感権限を外す更新もADMIN専用")
    void updateSensitivePermissionRemovalRequiresScopeAdmin() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "paid");
        PermissionGroupRequest request = new PermissionGroupRequest("paid", "MEMBER", List.of(PERM_ID_1));
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID).permissionId(PERM_ID_2).build()));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of(createPermissionEntity(PERM_ID_2, "SEND_PAID_TIMELINE")));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(accessControlService)
                .checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");
        assertThatThrownBy(() -> permissionGroupService.updatePermissionGroup(GROUP_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class);
        verify(accessControlService).checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");
        verify(permissionGroupRepository, never()).save(any());
        verify(permissionGroupPermissionRepository, never()).deleteByGroupId(anyLong());
    }

    @Test
    @DisplayName("F09.14: create は敏感権限だけ scope ADMIN、通常権限は従来の ADMIN 以上")
    void createPermissionGroup_sensitiveUsesStrictAdminAndNormalUsesAdminOrAbove() {
        PermissionGroupRequest sensitive = new PermissionGroupRequest(
                "paid", "MEMBER", List.of(SENSITIVE_PERM_ID));
        PermissionGroupRequest normal = new PermissionGroupRequest(
                "normal", "MEMBER", List.of(NORMAL_PERM_ID));
        given(permissionRepository.findByIdIn(List.of(SENSITIVE_PERM_ID)))
                .willReturn(List.of(createPermissionEntity(SENSITIVE_PERM_ID, "SEND_PAID_TIMELINE")));
        given(permissionRepository.findByIdIn(List.of(NORMAL_PERM_ID)))
                .willReturn(List.of(createPermissionEntity(NORMAL_PERM_ID, "MEMBER_MANAGE")));
        given(permissionGroupRepository.save(any(PermissionGroupEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        permissionGroupService.createPermissionGroup(SCOPE_ID, "TEAM", sensitive, CREATED_BY);
        permissionGroupService.createPermissionGroup(SCOPE_ID, "TEAM", normal, CREATED_BY);

        verify(accessControlService).checkScopeAdminOnly(CREATED_BY, SCOPE_ID, "TEAM");
        verify(accessControlService).checkAdminOrAbove(CREATED_BY, SCOPE_ID, "TEAM");
    }

    @Test
    @DisplayName("F09.14: update で敏感権限を追加する場合も strict ADMIN、拒否時は保存しない")
    void updatePermissionGroup_sensitiveAdditionRequiresScopeAdminBeforeMutation() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "normal");
        PermissionGroupRequest request = new PermissionGroupRequest(
                "paid", "MEMBER", List.of(SENSITIVE_PERM_ID));
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID)
                        .permissionId(NORMAL_PERM_ID).build()));
        given(userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(GROUP_ID)))
                .willReturn(List.of(USER_ID));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of(
                createPermissionEntity(NORMAL_PERM_ID, "MEMBER_MANAGE"),
                createPermissionEntity(SENSITIVE_PERM_ID, "VIEW_TIMELINE_COST")));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(accessControlService)
                .checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> permissionGroupService.updatePermissionGroup(GROUP_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(accessControlService).checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");
        verify(permissionGroupRepository, never()).save(any(PermissionGroupEntity.class));
        verify(permissionGroupPermissionRepository, never()).deleteByGroupId(anyLong());
    }

    @Test
    @DisplayName("F09.14: assign の敏感追加・敏感解除・空集合解除は strict ADMIN")
    void assignPermissionGroups_sensitiveTransitionsRequireScopeAdmin() {
        PermissionGroupEntity sensitiveGroup = createGroupEntity(GROUP_ID, "paid");
        PermissionGroupEntity normalGroup = createGroupEntity(NORMAL_PERM_ID, "normal");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID))
                .willReturn(List.of(sensitiveGroup, normalGroup));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(NORMAL_PERM_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(NORMAL_PERM_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(NORMAL_PERM_ID)
                        .permissionId(NORMAL_PERM_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID)
                        .permissionId(SENSITIVE_PERM_ID).build()));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of(
                createPermissionEntity(NORMAL_PERM_ID, "MEMBER_MANAGE"),
                createPermissionEntity(SENSITIVE_PERM_ID, "SEND_PAID_TIMELINE")));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(accessControlService)
                .checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");

        // 通常→敏感（追加）
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM",
                new UserPermissionGroupAssignRequest(List.of(NORMAL_PERM_ID, GROUP_ID)), USER_ID))
                .isInstanceOf(BusinessException.class);
        // 敏感→通常（置換）
        reset(userPermissionGroupRepository, permissionGroupPermissionRepository, permissionRepository);
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(sensitiveGroup, normalGroup));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(GROUP_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID)
                        .permissionId(SENSITIVE_PERM_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(NORMAL_PERM_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(NORMAL_PERM_ID)
                        .permissionId(NORMAL_PERM_ID).build()));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of(
                createPermissionEntity(NORMAL_PERM_ID, "MEMBER_MANAGE"),
                createPermissionEntity(SENSITIVE_PERM_ID, "SEND_PAID_TIMELINE")));
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM",
                new UserPermissionGroupAssignRequest(List.of(NORMAL_PERM_ID)), USER_ID))
                .isInstanceOf(BusinessException.class);
        // 敏感→空集合（解除）
        reset(userPermissionGroupRepository, permissionGroupPermissionRepository, permissionRepository);
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(sensitiveGroup));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(GROUP_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID)
                        .permissionId(SENSITIVE_PERM_ID).build()));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of(
                createPermissionEntity(SENSITIVE_PERM_ID, "SEND_PAID_TIMELINE")));
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of()), USER_ID))
                .isInstanceOf(BusinessException.class);

        verify(accessControlService, times(3)).checkScopeAdminOnly(USER_ID, SCOPE_ID, "TEAM");
        verify(userPermissionGroupRepository, never()).deleteByUserIdAndGroupIdIn(anyLong(), anyList());
        verify(userPermissionGroupRepository, never()).save(any(UserPermissionGroupEntity.class));
    }

    @Test
    @DisplayName("課金権限: update は変更前と変更後の権限IDを専用ガードへ渡す")
    void updatePermissionGroup_passesOldAndNewPermissionsToBillingGuard() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "billing");
        PermissionGroupRequest request = new PermissionGroupRequest(
                "normal", "DEPUTY_ADMIN", List.of(PERM_ID_2));
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder()
                        .groupId(GROUP_ID).permissionId(PERM_ID_1).build()));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(billingPermissionGroupGuard).authorizeMutation(
                        BillingPermissionGroupGuard.Operation.UPDATE,
                        CREATED_BY, SCOPE_ID, "TEAM", GROUP_ID,
                        List.of(PERM_ID_1), List.of(PERM_ID_2));

        assertThatThrownBy(() -> permissionGroupService.updatePermissionGroup(
                GROUP_ID, request, CREATED_BY)).isInstanceOf(BusinessException.class);

        verify(permissionGroupRepository, never()).save(any());
        verify(permissionGroupPermissionRepository, never()).deleteByGroupId(anyLong());
    }

    @Test
    @DisplayName("課金権限: delete と duplicate は変更前の権限IDを専用ガードへ渡す")
    void deleteAndDuplicate_passOldPermissionsToBillingGuard() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "billing");
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder()
                        .groupId(GROUP_ID).permissionId(PERM_ID_1).build()));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(billingPermissionGroupGuard).authorizeMutation(
                        BillingPermissionGroupGuard.Operation.DELETE,
                        CREATED_BY, SCOPE_ID, "TEAM", GROUP_ID,
                        List.of(PERM_ID_1), List.of());

        assertThatThrownBy(() -> permissionGroupService.deletePermissionGroup(GROUP_ID, CREATED_BY))
                .isInstanceOf(BusinessException.class);
        verify(permissionGroupRepository, never()).delete(any());

        reset(billingPermissionGroupGuard);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(billingPermissionGroupGuard).authorizeMutation(
                        BillingPermissionGroupGuard.Operation.DUPLICATE,
                        CREATED_BY, SCOPE_ID, "TEAM", GROUP_ID,
                        List.of(PERM_ID_1), List.of());
        assertThatThrownBy(() -> permissionGroupService.duplicatePermissionGroup(GROUP_ID, CREATED_BY))
                .isInstanceOf(BusinessException.class);
        verify(permissionGroupRepository, never()).save(any());
    }

    @Test
    @DisplayName("課金権限: 全解除でも現在割当のグループIDを専用ガードへ渡す")
    void clearAssignment_passesCurrentGroupsToBillingGuard() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "billing");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(GROUP_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder()
                        .groupId(GROUP_ID).permissionId(PERM_ID_1).build()));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(billingPermissionGroupGuard).authorizeAssignment(
                        CREATED_BY, USER_ID, SCOPE_ID, "TEAM",
                        List.of(GROUP_ID), List.of());

        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM",
                new UserPermissionGroupAssignRequest(List.of()), CREATED_BY))
                .isInstanceOf(BusinessException.class);

        verify(userPermissionGroupRepository, never())
                .deleteByUserIdAndGroupIdIn(anyLong(), anyList());
    }

    @Test
    @DisplayName("F09.14: update/delete は同期中に evict せず afterCommit 後に影響ユーザーのキーだけ失効")
    void mutationEvictsOnlyAffectedUsersAfterCommit() {
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("role-permissions")).willReturn(cache);
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "normal");
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of());
        given(permissionGroupRepository.save(any(PermissionGroupEntity.class))).willAnswer(i -> i.getArgument(0));
        given(userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(GROUP_ID)))
                .willReturn(List.of(USER_ID, USER_ID_2));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of());
        PermissionGroupRequest request = new PermissionGroupRequest("renamed", "MEMBER", List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            permissionGroupService.updatePermissionGroup(GROUP_ID, request, CREATED_BY);
            verify(cacheManager, never()).getCache(anyString());
            runAfterCommit();
            verify(cache).evict(USER_ID + ":TEAM:" + SCOPE_ID);
            verify(cache).evict(USER_ID_2 + ":TEAM:" + SCOPE_ID);
            verify(cache, times(2)).evict(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("F09.14: delete/assign も同期中は evict せず afterCommit に正しい scope キーだけ失効")
    void deleteAndAssignEvictAfterCommit() {
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("role-permissions")).willReturn(cache);
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "normal");
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of());
        given(userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(GROUP_ID)))
                .willReturn(List.of(USER_ID_2));
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));

        TransactionSynchronizationManager.initSynchronization();
        try {
            permissionGroupService.deletePermissionGroup(GROUP_ID, CREATED_BY);
            permissionGroupService.assignUserPermissionGroups(
                    USER_ID, SCOPE_ID, "TEAM",
                    new UserPermissionGroupAssignRequest(List.of(GROUP_ID)), CREATED_BY);
            verify(cacheManager, never()).getCache(anyString());
            runAfterCommit();
            verify(cache).evict(USER_ID_2 + ":TEAM:" + SCOPE_ID);
            verify(cache).evict(USER_ID + ":TEAM:" + SCOPE_ID);
            verify(cache, times(2)).evict(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("F09.14: rollback 相当では permission cache を失効しない")
    void rollbackDoesNotEvictPermissionCache() {
        Cache cache = mock(Cache.class);
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "normal");
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of());
        given(permissionGroupRepository.save(any(PermissionGroupEntity.class))).willAnswer(i -> i.getArgument(0));
        given(userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(GROUP_ID))).willReturn(List.of(USER_ID));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            permissionGroupService.updatePermissionGroup(
                    GROUP_ID, new PermissionGroupRequest("renamed", "MEMBER", List.of()), CREATED_BY);
            verify(cache, never()).evict(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("F09.14: 1 ユーザーの evict 例外や cacheManager 例外でも mutation は成功し他ユーザーへ継続")
    void cacheEvictionIsFailOpenAndContinuesPerUser() {
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("role-permissions")).willReturn(cache);
        doThrow(new IllegalStateException("first user cache failure"))
                .when(cache).evict(USER_ID + ":TEAM:" + SCOPE_ID);
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "normal");
        given(permissionGroupRepository.findByIdForUpdate(GROUP_ID)).willReturn(Optional.of(group));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of());
        given(permissionGroupRepository.save(any(PermissionGroupEntity.class))).willAnswer(i -> i.getArgument(0));
        given(userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(GROUP_ID)))
                .willReturn(List.of(USER_ID, USER_ID_2));
        given(permissionRepository.findByIdIn(anyList())).willReturn(List.of());

        permissionGroupService.updatePermissionGroup(
                GROUP_ID, new PermissionGroupRequest("renamed", "MEMBER", List.of()), CREATED_BY);
        verify(cache).evict(USER_ID + ":TEAM:" + SCOPE_ID);
        verify(cache).evict(USER_ID_2 + ":TEAM:" + SCOPE_ID);
        verify(cacheErrorHandler).handleCacheEvictError(any(RuntimeException.class), eq(cache),
                eq(USER_ID + ":TEAM:" + SCOPE_ID));

        reset(cacheManager);
        given(cacheManager.getCache("role-permissions"))
                .willThrow(new IllegalStateException("cache manager unavailable"));
        assertThatCode(() -> permissionGroupService.updatePermissionGroup(
                GROUP_ID, new PermissionGroupRequest("renamed again", "MEMBER", List.of()), CREATED_BY))
                .doesNotThrowAnyException();
        verify(cacheErrorHandler).handleCacheEvictError(any(RuntimeException.class), isNull(),
                eq(USER_ID + ":TEAM:" + SCOPE_ID));
        verify(cacheErrorHandler).handleCacheEvictError(any(RuntimeException.class), isNull(),
                eq(USER_ID_2 + ":TEAM:" + SCOPE_ID));
        verify(permissionGroupRepository, times(2)).save(any(PermissionGroupEntity.class));
    }

    @Test
    void assign非空で対象userが非所属ならROLE006かつmutationなし() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "target");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of(GROUP_ID)), CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("ROLE_006"));
        verify(userPermissionGroupRepository, never()).deleteByUserIdAndGroupIdIn(anyLong(), anyList());
        verify(userPermissionGroupRepository, never()).save(any(UserPermissionGroupEntity.class));
    }

    @Test
    void assign非空でtargetRole不一致ならROLE006かつmutationなし() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "target");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(accessControlService.resolveEffectiveRoleName(USER_ID, SCOPE_ID, "TEAM")).willReturn("MEMBER");
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of(GROUP_ID)), CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("ROLE_006"));
        verify(userPermissionGroupRepository, never()).deleteByUserIdAndGroupIdIn(anyLong(), anyList());
        verify(userPermissionGroupRepository, never()).save(any(UserPermissionGroupEntity.class));
    }

    @Test
    void assign正しいmemberRoleなら保存成功() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "target");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(userPermissionGroupRepository.save(any(UserPermissionGroupEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of(GROUP_ID)), CREATED_BY);
        verify(userPermissionGroupRepository).save(any(UserPermissionGroupEntity.class));
    }

    @Test
    void assign空集合は非所属でも既存割当を削除できる() {
        PermissionGroupEntity group = createGroupEntity(GROUP_ID, "cleanup");
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(GROUP_ID).build()));
        permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of()), CREATED_BY);
        verify(userPermissionGroupRepository).deleteByUserIdAndGroupIdIn(USER_ID, List.of(GROUP_ID));
        verify(accessControlService, never()).resolveEffectiveRoleName(anyLong(), anyLong(), anyString());
    }

    @Test
    void assign空集合で物理不在userはnoOp() {
        given(userRowLockService.lockAll(any(Long[].class)))
                .willReturn(Map.of(USER_ID, UserRowLockService.UserState.ABSENT));
        permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of()), CREATED_BY);
        verify(permissionGroupRepository, never()).findByTeamId(anyLong());
        verify(userPermissionGroupRepository, never()).deleteByUserIdAndGroupIdIn(anyLong(), anyList());
    }

    @Test
    void assign非空で退会済みuserはROLE006() {
        given(userRowLockService.lockAll(any(Long[].class)))
                .willReturn(Map.of(USER_ID, UserRowLockService.UserState.INELIGIBLE_EXISTING));
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                USER_ID, SCOPE_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of(GROUP_ID)), CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("ROLE_006"));
        verify(permissionGroupRepository, never()).findByTeamId(anyLong());
    }

    private void runAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }
}
