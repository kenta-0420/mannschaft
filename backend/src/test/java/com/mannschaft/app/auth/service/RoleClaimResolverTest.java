package com.mannschaft.app.auth.service;

import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link RoleClaimResolver} の単体テスト。
 *
 * <p>認可基盤完全根治 Phase 1（{@code docs/security/03_role_authority_model.md} §3.2）の核心ロジック。
 * 「SYSTEM_ADMIN ユーザーの roles に SYSTEM_ADMIN が含まれ、一般ユーザーには MEMBER のみ」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleClaimResolver 単体テスト")
class RoleClaimResolverTest {

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RoleClaimResolver roleClaimResolver;

    @Test
    @DisplayName("一般ユーザー: roles は [MEMBER] のみ（SYSTEM_ADMIN を含まない）")
    void resolveRoles_一般ユーザー_MEMBERのみ() {
        // Given
        Long userId = 100L;
        given(accessControlService.isSystemAdmin(userId)).willReturn(false);

        // When
        List<String> roles = roleClaimResolver.resolveRoles(userId);

        // Then
        assertThat(roles).containsExactly("MEMBER");
        assertThat(roles).doesNotContain("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("SYSTEM_ADMIN ユーザー: roles に MEMBER と SYSTEM_ADMIN の両方が含まれる")
    void resolveRoles_SYSTEM_ADMIN_両方含む() {
        // Given
        Long userId = 1L;
        given(accessControlService.isSystemAdmin(userId)).willReturn(true);

        // When
        List<String> roles = roleClaimResolver.resolveRoles(userId);

        // Then
        assertThat(roles).containsExactlyInAnyOrder("MEMBER", "SYSTEM_ADMIN");
        // MEMBER は基底ロールとして必ず先頭に来る
        assertThat(roles.get(0)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("判定は AccessControlService.isSystemAdmin に委譲される（user_roles 参照の一元化）")
    void resolveRoles_AccessControlServiceに委譲() {
        // Given
        Long userId = 42L;
        given(accessControlService.isSystemAdmin(userId)).willReturn(true);

        // When
        roleClaimResolver.resolveRoles(userId);

        // Then: isSystemAdmin が当該 userId で 1 回呼ばれる
        org.mockito.Mockito.verify(accessControlService).isSystemAdmin(userId);
    }
}
