package com.mannschaft.app.billing;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.security.BillingPermissionGroupGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("課金権限グループの自己昇格防止")
class BillingPermissionGroupGuardTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private PermissionGroupPermissionRepository groupPermissionRepository;
    @Mock
    private AuditLogService auditLogService;

    @Test
    @DisplayName("通常権限だけの更新は既存の権限管理へ委ねる")
    void nonBillingMutation_isNotProtected() {
        var guard = guard();
        given(permissionRepository.findByIdIn(List.of(1L)))
                .willReturn(List.of(permission(1L, "MANAGE_SCHEDULES", PermissionEntity.Scope.TEAM)));

        assertThat(guard.authorizeMutation(
                BillingPermissionGroupGuard.Operation.UPDATE,
                10L, 20L, "TEAM", 30L, List.of(), List.of(1L))).isFalse();

        verifyNoInteractions(accessControlService, auditLogService);
    }

    @Test
    @DisplayName("課金権限を含む作成は同一scopeのDEPUTY_ADMINを拒否し拒否監査を残す")
    void billingMutation_deputyIsDeniedAndAudited() {
        var guard = guard();
        given(permissionRepository.findByIdIn(List.of(2L)))
                .willReturn(List.of(permission(2L, "MANAGE_TEAM_BILLING", PermissionEntity.Scope.TEAM)));
        given(accessControlService.isAdmin(10L, 20L, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> guard.authorizeMutation(
                BillingPermissionGroupGuard.Operation.CREATE,
                10L, 20L, "TEAM", null, List.of(), List.of(2L)))
                .isInstanceOf(BusinessException.class);

        verify(auditLogService).record(eq("BILLING_PERMISSION_GROUP_DENIED"), eq(10L), eq(null),
                eq(20L), eq(null), eq(null), eq(null), eq(null), contains("CREATE"));
    }

    @Test
    @DisplayName("課金権限を含む更新は同一scopeの厳密ADMINだけを許可する")
    void billingMutation_adminIsAllowed() {
        var guard = guard();
        given(permissionRepository.findByIdIn(List.of(2L)))
                .willReturn(List.of(permission(2L, "MANAGE_TEAM_BILLING", PermissionEntity.Scope.TEAM)));
        given(accessControlService.isAdmin(10L, 20L, "TEAM")).willReturn(true);

        assertThat(guard.authorizeMutation(
                BillingPermissionGroupGuard.Operation.UPDATE,
                10L, 20L, "TEAM", 30L, List.of(2L), List.of())).isTrue();
    }

    @Test
    @DisplayName("課金権限グループの自己割当はADMIN本人でも拒否する")
    void billingAssignment_selfIsDenied() {
        var guard = guard();
        billingGroup(30L, 2L);

        assertThatThrownBy(() -> guard.authorizeAssignment(
                10L, 10L, 20L, "TEAM", List.of(30L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("課金権限グループは同一scopeのDEPUTY_ADMINだけへ割当できる")
    void billingAssignment_requiresDeputyRecipient() {
        var guard = guard();
        billingGroup(30L, 2L);
        given(accessControlService.isAdmin(10L, 20L, "TEAM")).willReturn(true);
        given(accessControlService.getRoleName(11L, 20L, "TEAM")).willReturn("ADMIN");

        assertThatThrownBy(() -> guard.authorizeAssignment(
                10L, 11L, 20L, "TEAM", List.of(30L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("同一scope ADMINからDEPUTY_ADMINへの課金権限割当は許可する")
    void billingAssignment_adminToDeputyIsAllowed() {
        var guard = guard();
        billingGroup(30L, 2L);
        given(accessControlService.isAdmin(10L, 20L, "TEAM")).willReturn(true);
        given(accessControlService.getRoleName(11L, 20L, "TEAM")).willReturn("DEPUTY_ADMIN");

        assertThat(guard.authorizeAssignment(
                10L, 11L, 20L, "TEAM", List.of(30L))).isTrue();
        verify(auditLogService, never()).record(any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    private void billingGroup(Long groupId, Long permissionId) {
        given(groupPermissionRepository.findByGroupId(groupId)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder()
                        .groupId(groupId).permissionId(permissionId).build()));
        given(permissionRepository.findByIdIn(List.of(permissionId)))
                .willReturn(List.of(permission(
                        permissionId, "MANAGE_TEAM_BILLING", PermissionEntity.Scope.TEAM)));
    }

    private PermissionEntity permission(Long id, String name, PermissionEntity.Scope scope) {
        return PermissionEntity.builder().id(id).name(name).displayName(name).scope(scope).build();
    }

    private BillingPermissionGroupGuard guard() {
        return new BillingPermissionGroupGuard(
                accessControlService, permissionRepository, groupPermissionRepository, auditLogService);
    }
}
