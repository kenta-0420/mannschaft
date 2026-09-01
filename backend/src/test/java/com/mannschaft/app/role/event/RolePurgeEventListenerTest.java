package com.mannschaft.app.role.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RolePurgeEventListener} 単体テスト。
 *
 * <p>柱①ADMINゼロ根治 検分反映（P1-1）で実処理を {@link RolePurgeScopeExecutor}
 * （別Bean・{@code REQUIRES_NEW}）へ委譲したため、本テストは
 * {@code scopeExecutor.processScope(...)} の呼び出しを検証する（Mockito では
 * 実トランザクション挙動は検証できないため、「1スコープ失敗が他スコープを
 * 巻き添えロールバックしない」こと自体は Testcontainers IT
 * {@code RolePurgeScopeExecutorTransactionIsolationIT} で検証する）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RolePurgeEventListener 単体テスト")
class RolePurgeEventListenerTest {

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RolePurgeScopeExecutor scopeExecutor;
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
    @DisplayName("正常系: 複数 user_roles 行を scopeExecutor.processScope 経由で全件処理")
    void 正常_全件削除() {
        UserRoleEntity org = buildOrgRole(1L, 10L);
        UserRoleEntity team = buildTeamRole(2L, 20L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(org, team));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(scopeExecutor).processScope(eq(USER_ID), eq(10L), eq("ORGANIZATION"), any(Boolean.class), any(UUID.class));
        verify(scopeExecutor).processScope(eq(USER_ID), eq(20L), eq("TEAM"), any(Boolean.class), any(UUID.class));
    }

    @Test
    @DisplayName("異常系: 1件目で例外でも 2件目以降は継続")
    void 異常_1件目失敗_他継続() {
        UserRoleEntity org1 = buildOrgRole(1L, 10L);
        UserRoleEntity org2 = buildOrgRole(2L, 11L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(org1, org2));
        willThrow(new RuntimeException("DB error"))
                .given(scopeExecutor).processScope(eq(USER_ID), eq(10L), eq("ORGANIZATION"), any(Boolean.class), any(UUID.class));

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(scopeExecutor).processScope(eq(USER_ID), eq(10L), eq("ORGANIZATION"), any(Boolean.class), any(UUID.class));
        verify(scopeExecutor).processScope(eq(USER_ID), eq(11L), eq("ORGANIZATION"), any(Boolean.class), any(UUID.class));
    }

    @Test
    @DisplayName("正常系: 0件なら processScope は呼ばれない")
    void 正常_0件() {
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of());

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(scopeExecutor, never()).processScope(
                any(), any(), any(), any(Boolean.class), any());
    }

    @Test
    @DisplayName("正常系: SYSTEM_ADMIN（team_id・organization_id 共に NULL）はスキップ")
    void 正常_SystemAdmin_スキップ() {
        UserRoleEntity sysadmin = buildSystemAdmin(1L);
        UserRoleEntity org = buildOrgRole(2L, 10L);
        given(userRoleRepository.findAllByUserId(USER_ID))
                .willReturn(List.of(sysadmin, org));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        // SYSTEM_ADMIN は呼ばれず、ORG ロールだけ processScope される
        verify(scopeExecutor, times(1)).processScope(eq(USER_ID), eq(10L), eq("ORGANIZATION"), any(Boolean.class), any(UUID.class));
    }

    @Test
    @DisplayName("正常系: roleRepositoryでADMINロールが見つかればisAdmin=trueでprocessScopeが呼ばれる")
    void 正常_ADMINロールはisAdminTrueで呼ばれる() {
        UserRoleEntity org = buildOrgRole(1L, 10L);
        given(userRoleRepository.findAllByUserId(USER_ID)).willReturn(List.of(org));
        given(roleRepository.findById(1L)).willReturn(java.util.Optional.of(
                com.mannschaft.app.role.entity.RoleEntity.builder().id(1L).name("ADMIN").build()));

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(scopeExecutor).processScope(eq(USER_ID), eq(10L), eq("ORGANIZATION"), eq(true), any(UUID.class));
    }
}
