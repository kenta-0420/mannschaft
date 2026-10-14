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
    @DisplayName("指定スコープの権限だけを名前順で返し、ADMIN 専用の権限に adminOnlyGrantable を立てる")
    void listsOnlyRequestedScopeSortedWithAdminOnlyFlag() {
        given(permissionRepository.findByScope(PermissionEntity.Scope.TEAM)).willReturn(List.of(
                permission(2, "MANAGE_SCHEDULES", PermissionEntity.Scope.TEAM),
                permission(1, "MANAGE_ORG_AFFILIATION", PermissionEntity.Scope.TEAM)));

        List<PermissionCatalogItemResponse> result = service.list("TEAM");

        assertThat(result).extracting(PermissionCatalogItemResponse::getName)
                .containsExactly("MANAGE_ORG_AFFILIATION", "MANAGE_SCHEDULES");
        assertThat(result.get(0).isAdminOnlyGrantable()).isTrue();
        assertThat(result.get(1).isAdminOnlyGrantable()).isFalse();
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getDisplayName()).isEqualTo("MANAGE_ORG_AFFILIATION 表示名");
        assertThat(result.get(0).getScope()).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("PLATFORM や不正なスコープは 400 相当の BusinessException で、検索しない")
    void rejectsPlatformAndUnknownScope() {
        assertThatThrownBy(() -> service.list("PLATFORM")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list("NOPE")).isInstanceOf(BusinessException.class);
        verify(permissionRepository, never()).findByScope(org.mockito.ArgumentMatchers.any());
    }
}
