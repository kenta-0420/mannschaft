package com.mannschaft.app.role.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.role.service.RoleSuccessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RolePurgeEventListener 単体テスト")
class RolePurgeEventListenerTest {

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private RoleSuccessionService roleSuccessionService;
    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;

    @InjectMocks
    private RolePurgeEventListener listener;

    private static final Long USER_ID = 100L;

    private UserRoleEntity buildOrgRole(Long id, Long organizationId) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(USER_ID)
                .roleId(1L)
                .organizationId(organizationId)
                .build();
    }

    private UserRoleEntity buildTeamRole(Long id, Long teamId) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(USER_ID)
                .roleId(1L)
                .teamId(teamId)
                .build();
    }

    private UserRoleEntity buildSystemAdmin(Long id) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(USER_ID)
                .roleId(99L)
                .build();
    }

    @Test
    @DisplayName("正常系: 複数 user_roles 行を removeMemberWithoutAdminCheck 経由で全件削除")
    void 正常_全件削除() {
        UserRoleEntity org = buildOrgRole(1L, 10L);
        UserRoleEntity team = buildTeamRole(2L, 20L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(org, team));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(roleService).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", USER_ID);
        verify(roleService).removeMemberWithoutAdminCheck(20L, "TEAM", USER_ID);
    }

    @Test
    @DisplayName("異常系: 1件目で例外でも 2件目以降は継続")
    void 異常_1件目失敗_他継続() {
        UserRoleEntity org1 = buildOrgRole(1L, 10L);
        UserRoleEntity org2 = buildOrgRole(2L, 11L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(org1, org2));
        willThrow(new RuntimeException("DB error"))
                .given(roleService).removeMemberWithoutAdminCheck(eq(10L), eq("ORGANIZATION"), eq(USER_ID));

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(roleService).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", USER_ID);
        verify(roleService).removeMemberWithoutAdminCheck(11L, "ORGANIZATION", USER_ID);
    }

    @Test
    @DisplayName("正常系: 0件なら removeMemberWithoutAdminCheck は呼ばれない")
    void 正常_0件() {
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of());

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(roleService, never()).removeMemberWithoutAdminCheck(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("正常系: SYSTEM_ADMIN（team_id・organization_id 共に NULL）はスキップ")
    void 正常_SystemAdmin_スキップ() {
        UserRoleEntity sysadmin = buildSystemAdmin(1L);
        UserRoleEntity org = buildOrgRole(2L, 10L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(sysadmin, org));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        // SYSTEM_ADMIN は呼ばれず、ORG ロールだけ removeMember される
        verify(roleService, times(1)).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", USER_ID);
    }
}
