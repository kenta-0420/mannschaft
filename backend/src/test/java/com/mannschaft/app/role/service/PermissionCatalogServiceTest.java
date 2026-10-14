package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.PermissionCatalogItemResponse;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PermissionCatalogService} の単体テスト（F01.2 02_api_design.md の GET /api/v1/permissions）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionCatalogService 単体テスト")
class PermissionCatalogServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private PermissionCatalogService service;

    private PermissionEntity permission(long id, String name, PermissionEntity.Scope scope) {
        return PermissionEntity.builder()
                .id(id).name(name).displayName(name + " 表示名").scope(scope)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("TEAM・ORGANIZATION のどちらで問い合わせても、権限グループに含められる権限（TEAM/ORGANIZATION 分類）を名前順で返す。PLATFORM 分類は含めない")
    void listsAssignableScopesForBothTeamAndOrganization() {
        given(permissionRepository.findByScopeIn(PermissionCatalogService.GROUP_ASSIGNABLE_SCOPES)).willReturn(List.of(
                permission(3, "MANAGE_CONTENT", PermissionEntity.Scope.TEAM),
                permission(2, "MANAGE_SCHEDULES", PermissionEntity.Scope.TEAM),
                permission(4, "MANAGE_FILES", PermissionEntity.Scope.ORGANIZATION),
                permission(1, "MANAGE_ORG_AFFILIATION", PermissionEntity.Scope.TEAM)));

        List<PermissionCatalogItemResponse> forTeam = service.list("TEAM");
        List<PermissionCatalogItemResponse> forOrg = service.list("ORGANIZATION");

        assertThat(forTeam).extracting(PermissionCatalogItemResponse::getName)
                .containsExactly("MANAGE_CONTENT", "MANAGE_FILES", "MANAGE_ORG_AFFILIATION", "MANAGE_SCHEDULES");
        // 組織向けにも TEAM 分類の MANAGE_CONTENT が出る（組織 DEPUTY_ADMIN への委任に必要）
        assertThat(forOrg).extracting(PermissionCatalogItemResponse::getName).isEqualTo(
                forTeam.stream().map(PermissionCatalogItemResponse::getName).toList());
        assertThat(forTeam.get(2).isAdminOnlyGrantable()).isTrue();
        assertThat(forTeam.get(0).isAdminOnlyGrantable()).isFalse();
        assertThat(forTeam.get(2).getId()).isEqualTo(1L);
        assertThat(forTeam.get(2).getDisplayName()).isEqualTo("MANAGE_ORG_AFFILIATION 表示名");
        assertThat(forTeam.get(2).getScope()).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("グループ可否の判定は作成・更新と共用: PLATFORM は不可、TEAM/ORGANIZATION は可")
    void assignabilityRuleIsShared() {
        assertThat(PermissionCatalogService.isGroupAssignable(permission(1, "A", PermissionEntity.Scope.TEAM))).isTrue();
        assertThat(PermissionCatalogService.isGroupAssignable(permission(1, "A", PermissionEntity.Scope.ORGANIZATION))).isTrue();
        assertThat(PermissionCatalogService.isGroupAssignable(permission(1, "A", PermissionEntity.Scope.PLATFORM))).isFalse();
    }

    @Test
    @DisplayName("PLATFORM や不正なスコープは 400 相当の BusinessException で、検索しない")
    void rejectsPlatformAndUnknownScope() {
        assertThatThrownBy(() -> service.list("PLATFORM")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list("NOPE")).isInstanceOf(BusinessException.class);
        verify(permissionRepository, never()).findByScopeIn(org.mockito.ArgumentMatchers.any());
    }
}
