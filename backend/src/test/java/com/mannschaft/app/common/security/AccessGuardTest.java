package com.mannschaft.app.common.security;

import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AccessGuard} の単体テスト。
 *
 * <p>per-scope SpEL ガードの各メソッド（isScopeAdmin / isScopeStrictAdmin / isScopeMember /
 * hasScopePermission）について、以下の振る舞いを検証する:</p>
 * <ul>
 *   <li>SYSTEM_ADMIN は当該スコープのロール如何に関わらず常に true（短絡）</li>
 *   <li>当該スコープの ADMIN/DEPUTY_ADMIN は許可、それ以外は拒否</li>
 *   <li>null / 非認証 / userId パース失敗は false（DB を参照せず早期 false）</li>
 * </ul>
 *
 * <p>設計書: docs/security/03_role_authority_model.md §3.3</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessGuard 単体テスト")
class AccessGuardTest {

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private AccessGuard accessGuard;

    private static final Long USER_ID = 7L;
    private static final Long SCOPE_ID = 42L;
    private static final String SCOPE_TYPE = "TEAM";

    /** ユーザー ID を principal name に持つ認証済み Authentication を生成する。 */
    private Authentication authedAs(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "N/A", List.of());
    }

    // ========================================
    // 非認証・null・パース失敗
    // ========================================

    @Nested
    @DisplayName("非認証・null・パース失敗は常に false（DB 参照なし）")
    class Unauthenticated {

        @Test
        @DisplayName("authentication が null なら全メソッド false")
        void nullAuthentication() {
            assertThat(accessGuard.isScopeAdmin(null, SCOPE_ID, SCOPE_TYPE)).isFalse();
            assertThat(accessGuard.isScopeStrictAdmin(null, SCOPE_ID, SCOPE_TYPE)).isFalse();
            assertThat(accessGuard.isScopeMember(null, SCOPE_ID, SCOPE_TYPE)).isFalse();
            assertThat(accessGuard.hasScopePermission(null, SCOPE_ID, SCOPE_TYPE, "P")).isFalse();
            verify(accessControlService, never()).isSystemAdmin(org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("isAuthenticated()=false なら false")
        void notAuthenticated() {
            Authentication auth = new UsernamePasswordAuthenticationToken("5", "N/A");
            auth.setAuthenticated(false);
            assertThat(accessGuard.isScopeAdmin(auth, SCOPE_ID, SCOPE_TYPE)).isFalse();
            verify(accessControlService, never()).isSystemAdmin(org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("principal name が数値でなければ false")
        void nonNumericPrincipal() {
            Authentication auth = new UsernamePasswordAuthenticationToken("not-a-number", "N/A", List.of());
            assertThat(accessGuard.isScopeAdmin(auth, SCOPE_ID, SCOPE_TYPE)).isFalse();
            verify(accessControlService, never()).isSystemAdmin(org.mockito.ArgumentMatchers.anyLong());
        }
    }

    // ========================================
    // isScopeAdmin
    // ========================================

    @Nested
    @DisplayName("isScopeAdmin")
    class IsScopeAdmin {

        @Test
        @DisplayName("SYSTEM_ADMIN は当該スコープのロールに関わらず true（短絡）")
        void systemAdminShortCircuits() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            boolean result = accessGuard.isScopeAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE);

            assertThat(result).isTrue();
            // 短絡したので per-scope 判定は呼ばれない
            verify(accessControlService, never()).isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE);
        }

        @Test
        @DisplayName("当該スコープの ADMIN/DEPUTY なら true")
        void scopeAdminAllowed() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);

            assertThat(accessGuard.isScopeAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isTrue();
        }

        @Test
        @DisplayName("非 SYSTEM_ADMIN かつ当該スコープ非 ADMIN なら false（他団体 ADMIN を弾く）")
        void nonAdminDenied() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(false);

            assertThat(accessGuard.isScopeAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isFalse();
        }
    }

    // ========================================
    // isScopeStrictAdmin
    // ========================================

    @Nested
    @DisplayName("isScopeStrictAdmin")
    class IsScopeStrictAdmin {

        @Test
        @DisplayName("SYSTEM_ADMIN は true（短絡）")
        void systemAdminShortCircuits() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            assertThat(accessGuard.isScopeStrictAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isTrue();
            verify(accessControlService, never()).isAdmin(USER_ID, SCOPE_ID, SCOPE_TYPE);
        }

        @Test
        @DisplayName("当該スコープの ADMIN（DEPUTY 除く）なら true")
        void strictAdminAllowed() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdmin(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);
            assertThat(accessGuard.isScopeStrictAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN（isAdmin=false）なら false")
        void deputyDenied() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdmin(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(false);
            assertThat(accessGuard.isScopeStrictAdmin(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isFalse();
        }
    }

    // ========================================
    // isScopeMember
    // ========================================

    @Nested
    @DisplayName("isScopeMember")
    class IsScopeMember {

        @Test
        @DisplayName("SYSTEM_ADMIN は true（短絡）")
        void systemAdminShortCircuits() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            assertThat(accessGuard.isScopeMember(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isTrue();
            verify(accessControlService, never()).isMember(USER_ID, SCOPE_ID, SCOPE_TYPE);
        }

        @Test
        @DisplayName("当該スコープのメンバーなら true")
        void memberAllowed() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);
            assertThat(accessGuard.isScopeMember(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isTrue();
        }

        @Test
        @DisplayName("非メンバーなら false")
        void nonMemberDenied() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(false);
            assertThat(accessGuard.isScopeMember(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE)).isFalse();
        }
    }

    // ========================================
    // hasScopePermission
    // ========================================

    @Nested
    @DisplayName("hasScopePermission")
    class HasScopePermission {

        private static final String PERMISSION = "ATTENDANCE_DISCLOSE";

        @Test
        @DisplayName("SYSTEM_ADMIN は permission 如何に関わらず true（短絡）")
        void systemAdminShortCircuits() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            assertThat(accessGuard.hasScopePermission(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE, PERMISSION)).isTrue();
            verify(accessControlService, never()).hasPermission(USER_ID, SCOPE_ID, SCOPE_TYPE, PERMISSION);
        }

        @Test
        @DisplayName("当該スコープで permission を保有していれば true")
        void permissionAllowed() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasPermission(USER_ID, SCOPE_ID, SCOPE_TYPE, PERMISSION)).willReturn(true);
            assertThat(accessGuard.hasScopePermission(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE, PERMISSION)).isTrue();
        }

        @Test
        @DisplayName("permission 非保有なら false")
        void permissionDenied() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasPermission(USER_ID, SCOPE_ID, SCOPE_TYPE, PERMISSION)).willReturn(false);
            assertThat(accessGuard.hasScopePermission(authedAs(USER_ID), SCOPE_ID, SCOPE_TYPE, PERMISSION)).isFalse();
        }
    }
}
