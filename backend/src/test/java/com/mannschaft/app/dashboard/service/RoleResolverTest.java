package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.ViewerRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F02.2.1: {@link RoleResolver} の単体テスト。
 *
 * <p>{@link AccessControlService} をモックして、戻り値の正規化が
 * 設計書 §5 の規約通りに行われるかを網羅検証する:</p>
 *
 * <ul>
 *   <li>SYSTEM_ADMIN フラグ true → SYSTEM_ADMIN（最優先バイパス）</li>
 *   <li>{@code "ADMIN" / "DEPUTY_ADMIN" / "MEMBER" / "SUPPORTER"} → 同名 ViewerRole</li>
 *   <li>{@code "GUEST"} または null → PUBLIC</li>
 *   <li>{@code AccessControlService.getRoleName(userId, scopeId, scopeType)} の引数順</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleResolver 単体テスト")
class RoleResolverTest {

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RoleResolver roleResolver;

    private static final Long USER_ID = 1L;
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORG = "ORGANIZATION";
    private static final Long SCOPE_ID = 100L;

    // ========================================
    // SYSTEM_ADMIN
    // ========================================

    @Nested
    @DisplayName("SYSTEM_ADMIN 判定")
    class SystemAdminBypass {

        @Test
        @DisplayName("isSystemAdmin == true → SYSTEM_ADMIN（getRoleName を呼ばない）")
        void systemAdmin_最優先バイパス() {
            // Given
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            // When
            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            // Then
            assertThat(result).isEqualTo(ViewerRole.SYSTEM_ADMIN);
            // SYSTEM_ADMIN ならスコープ内ロール解決をしないことを確認
            verify(accessControlService, never()).getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM);
        }
    }

    // ========================================
    // 各ロール文字列の正規化
    // ========================================

    @Nested
    @DisplayName("AccessControlService.getRoleName の戻り値マッピング")
    class RoleNameMapping {

        @Test
        @DisplayName("\"ADMIN\" → ViewerRole.ADMIN")
        void admin文字列_ADMIN() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("ADMIN");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.ADMIN);
        }

        @Test
        @DisplayName("\"DEPUTY_ADMIN\" → ViewerRole.DEPUTY_ADMIN")
        void deputyAdmin文字列_DEPUTY_ADMIN() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("DEPUTY_ADMIN");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.DEPUTY_ADMIN);
        }

        @Test
        @DisplayName("\"MEMBER\" → ViewerRole.MEMBER")
        void member文字列_MEMBER() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("MEMBER");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.MEMBER);
        }

        @Test
        @DisplayName("\"SUPPORTER\" → ViewerRole.SUPPORTER")
        void supporter文字列_SUPPORTER() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("SUPPORTER");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.SUPPORTER);
        }

        @Test
        @DisplayName("\"GUEST\" → ViewerRole.PUBLIC")
        void guest文字列_PUBLIC() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("GUEST");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.PUBLIC);
        }

        @Test
        @DisplayName("null（メンバーシップなし） → ViewerRole.PUBLIC")
        void null戻り値_PUBLIC() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn(null);

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.PUBLIC);
        }

        @Test
        @DisplayName("未知のロール文字列 → PUBLIC（フェイルセーフ）")
        void 未知ロール文字列_PUBLIC() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("UNKNOWN_ROLE");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            // 安全側設計: 未知の値は PUBLIC に丸める
            assertThat(result).isEqualTo(ViewerRole.PUBLIC);
        }

        @Test
        @DisplayName("小文字の \"member\" → ViewerRole.MEMBER（toUpperCase で正規化）")
        void 小文字戻り値_正規化() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_TEAM))
                    .willReturn("member");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.MEMBER);
        }
    }

    // ========================================
    // 引数順
    // ========================================

    @Nested
    @DisplayName("AccessControlService.getRoleName の引数順")
    class ArgumentOrder {

        @Test
        @DisplayName("getRoleName(userId, scopeId, scopeType) の順で呼ばれる（実コードのシグネチャに従う）")
        void 引数順_userId_scopeId_scopeType() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_ORG))
                    .willReturn("MEMBER");

            ViewerRole result = roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_ORG, SCOPE_ID);

            assertThat(result).isEqualTo(ViewerRole.MEMBER);
            // 引数順を厳密に検証: userId → scopeId → scopeType
            verify(accessControlService).getRoleName(USER_ID, SCOPE_ID, SCOPE_TYPE_ORG);
        }
    }

    // ========================================
    // バリデーション
    // ========================================

    @Nested
    @DisplayName("引数バリデーション")
    class ArgValidation {

        @Test
        @DisplayName("userId == null → IllegalArgumentException")
        void userIdNull() {
            assertThatThrownBy(() ->
                    roleResolver.resolveViewerRole(null, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId");
        }

        @Test
        @DisplayName("scopeType == null → IllegalArgumentException")
        void scopeTypeNull() {
            assertThatThrownBy(() ->
                    roleResolver.resolveViewerRole(USER_ID, null, SCOPE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scopeType");
        }

        @Test
        @DisplayName("scopeType == 空文字 → IllegalArgumentException")
        void scopeTypeBlank() {
            assertThatThrownBy(() ->
                    roleResolver.resolveViewerRole(USER_ID, "  ", SCOPE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scopeType");
        }

        @Test
        @DisplayName("scopeId == null → IllegalArgumentException")
        void scopeIdNull() {
            assertThatThrownBy(() ->
                    roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scopeId");
        }
    }
}
