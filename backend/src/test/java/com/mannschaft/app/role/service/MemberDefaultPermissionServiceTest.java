package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.role.dto.MemberPermissionSetting;
import com.mannschaft.app.role.dto.MemberPermissionUpdateItem;
import com.mannschaft.app.role.dto.MemberPermissionUpdateRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.TeamRolePermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.TeamRolePermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;

/** MEMBER 既定権限の完全指定・保存・キャッシュ世代更新を検証する。 */
@ExtendWith(MockitoExtension.class)
class MemberDefaultPermissionServiceTest {

    @Mock private RoleRepository roleRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private TeamRolePermissionRepository teamRolePermissionRepository;
    @Mock private RolePermissionCacheGenerationService cacheGenerationService;
    @Mock private AccessControlService accessControlService;
    @InjectMocks private MemberDefaultPermissionService service;

    private RoleEntity memberRole;
    private List<PermissionEntity> permissions;

    @BeforeEach
    void setUp() {
        memberRole = RoleEntity.builder().id(10L).name("MEMBER").priority(4).build();
        permissions = List.of(
                permission(1L, "MANAGE_SCHEDULES"),
                permission(2L, "MANAGE_FILES"),
                permission(3L, "MANAGE_POSTS"));
        lenient().when(roleRepository.findByName("MEMBER")).thenReturn(Optional.of(memberRole));
        lenient().when(permissionRepository.findByNameIn(MemberDefaultPermissionService.DEFAULT_PERMISSION_NAMES))
                .thenReturn(permissions);
        lenient().when(rolePermissionRepository.findByRoleId(10L)).thenReturn(List.of(
                rolePermission(1L, true), rolePermission(2L, true), rolePermission(3L, true)));
    }

    @Test
    void 未設定なら3権限を有効として返す() {
        given(teamRolePermissionRepository.findByScopeTypeAndScopeIdAndRoleId("TEAM", 20L, 10L))
                .willReturn(List.of());

        assertThat(service.get("TEAM", 20L, 99L).permissions())
                .extracting(MemberPermissionSetting::enabled)
                .containsExactly(true, true, true);
        assertThat(service.get("TEAM", 20L, 99L).permissions())
                .extracting(MemberPermissionSetting::inherited)
                .containsExactly(true, true, true);
    }

    @Test
    void 完全指定を保存してスコープ世代を進める() {
        MemberPermissionUpdateRequest request = new MemberPermissionUpdateRequest(List.of(
                new MemberPermissionUpdateItem("MANAGE_SCHEDULES", false),
                new MemberPermissionUpdateItem("MANAGE_FILES", true),
                new MemberPermissionUpdateItem("MANAGE_POSTS", false)));

        var response = service.update("ORGANIZATION", 30L, 99L, request);

        ArgumentCaptor<TeamRolePermissionEntity> captor =
                ArgumentCaptor.forClass(TeamRolePermissionEntity.class);
        verify(teamRolePermissionRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(TeamRolePermissionEntity::getIsEnabled)
                .containsExactly(false, true, false);
        verify(cacheGenerationService).incrementGeneration("ORGANIZATION", 30L);
        assertThat(response.permissions()).extracting(MemberPermissionSetting::enabled)
                .containsExactly(false, true, false);
    }

    @Test
    void 権限の重複や欠落を拒否する() {
        MemberPermissionUpdateRequest request = new MemberPermissionUpdateRequest(List.of(
                new MemberPermissionUpdateItem("MANAGE_FILES", true),
                new MemberPermissionUpdateItem("MANAGE_FILES", false),
                new MemberPermissionUpdateItem("MANAGE_POSTS", true)));

        assertThatThrownBy(() -> service.update("TEAM", 20L, 99L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void admin本人でなければ拒否する() {
        doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                .when(accessControlService).checkScopeAdminOnly(99L, 20L, "TEAM");

        assertThatThrownBy(() -> service.get("TEAM", 20L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("COMMON_002");
    }

    @Test
    void member天井が欠落していれば更新を拒否する() {
        given(rolePermissionRepository.findByRoleId(10L)).willReturn(List.of(
                rolePermission(1L, true), rolePermission(2L, true)));
        MemberPermissionUpdateRequest request = new MemberPermissionUpdateRequest(List.of(
                new MemberPermissionUpdateItem("MANAGE_SCHEDULES", true),
                new MemberPermissionUpdateItem("MANAGE_FILES", true),
                new MemberPermissionUpdateItem("MANAGE_POSTS", true)));

        assertThatThrownBy(() -> service.update("TEAM", 20L, 99L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("天井が欠落");
    }

    private PermissionEntity permission(Long id, String name) {
        return PermissionEntity.builder().id(id).name(name).displayName(name)
                .scope(PermissionEntity.Scope.TEAM).build();
    }

    private TeamRolePermissionEntity override(Long permissionId, boolean enabled) {
        return TeamRolePermissionEntity.builder().scopeType("ORGANIZATION").scopeId(30L)
                .roleId(10L).permissionId(permissionId).isEnabled(enabled).build();
    }

    private com.mannschaft.app.role.entity.RolePermissionEntity rolePermission(
            Long permissionId, boolean enabled) {
        return com.mannschaft.app.role.entity.RolePermissionEntity.builder()
                .roleId(10L).permissionId(permissionId).isDefault(enabled).build();
    }
}
