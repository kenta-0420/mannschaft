package com.mannschaft.app.role.event;

import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.role.service.RoleSuccessionService;
import com.mannschaft.app.role.service.RoleSuccessionService.PurgeSuccessionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 柱①「ADMINゼロ根治」検分反映（Codex第4巡 P1-a） — {@link RolePurgeScopeExecutor} の受け入れテスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §9 / §13。
 * {@code forceTransferForPurge} が {@code RETRY_LATER} を返したとき、呼び出し元が
 * {@code removeMemberWithoutAdminCheck} を実行しない（＝承継未完了のまま旧 ADMIN 行を
 * 消してしまい ADMIN 0 が発生する事故を防ぐ）ことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RolePurgeScopeExecutor 受け入れテスト（Codex第4巡P1-a・柱①ADMINゼロ根治）")
class RolePurgeScopeExecutorTest {

    @Mock
    private RoleService roleService;
    @Mock
    private RoleSuccessionService roleSuccessionService;

    @InjectMocks
    private RolePurgeScopeExecutor executor;

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 100L;

    @Test
    @DisplayName("P1-a: forceTransferForPurgeがRETRY_LATERを返した場合、"
            + "removeMemberWithoutAdminCheckは実行されず例外で失敗扱いになる")
    void RETRY_LATER時はremoveMemberWithoutAdminCheckが呼ばれない() {
        UUID purgeId = UUID.randomUUID();
        given(roleSuccessionService.forceTransferForPurge(SCOPE_ID, "TEAM", USER_ID, purgeId))
                .willReturn(PurgeSuccessionResult.RETRY_LATER);

        assertThatThrownBy(() -> executor.processScope(USER_ID, SCOPE_ID, "TEAM", true, purgeId))
                .isInstanceOf(IllegalStateException.class);

        verify(roleService, never()).removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", USER_ID);
    }

    @Test
    @DisplayName("P1-a: forceTransferForPurgeがSUCCEEDEDを返した場合はremoveMemberWithoutAdminCheckが実行される")
    void SUCCEEDED時はremoveMemberWithoutAdminCheckが呼ばれる() {
        UUID purgeId = UUID.randomUUID();
        given(roleSuccessionService.forceTransferForPurge(SCOPE_ID, "TEAM", USER_ID, purgeId))
                .willReturn(PurgeSuccessionResult.SUCCEEDED);

        assertThatCode(() -> executor.processScope(USER_ID, SCOPE_ID, "TEAM", true, purgeId))
                .doesNotThrowAnyException();

        verify(roleService).removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", USER_ID);
    }

    @Test
    @DisplayName("P1-a: forceTransferForPurgeがARCHIVEDを返した場合もremoveMemberWithoutAdminCheckが実行される")
    void ARCHIVED時もremoveMemberWithoutAdminCheckが呼ばれる() {
        UUID purgeId = UUID.randomUUID();
        given(roleSuccessionService.forceTransferForPurge(SCOPE_ID, "TEAM", USER_ID, purgeId))
                .willReturn(PurgeSuccessionResult.ARCHIVED);

        assertThatCode(() -> executor.processScope(USER_ID, SCOPE_ID, "TEAM", true, purgeId))
                .doesNotThrowAnyException();

        verify(roleService).removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", USER_ID);
    }

    @Test
    @DisplayName("isAdmin=falseの場合はforceTransferForPurgeを呼ばずremoveMemberWithoutAdminCheckのみ実行する")
    void isAdminがfalseならforceTransferForPurgeは呼ばれない() {
        UUID purgeId = UUID.randomUUID();

        executor.processScope(USER_ID, SCOPE_ID, "TEAM", false, purgeId);

        verify(roleSuccessionService, never()).forceTransferForPurge(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(roleService).removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", USER_ID);
    }
}
