package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingAccessRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** 利用者向け課金 API 専用の fail-closed 認可契約。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAccessGuard 認可契約")
class BillingAccessGuardTest {

    @Mock
    private BillingAccessRepository billingAccessRepository;

    @InjectMocks
    private BillingAccessGuard guard;

    @Test
    @DisplayName("USER は認証主体本人の scope だけを管理できる")
    void userScope_allowsOnlySelf() {
        assertThat(guard.canManage(authentication("ROLE_MEMBER"), EntitlementScopeKind.USER, 42L)).isTrue();
        assertThat(guard.canManage(authentication("ROLE_MEMBER"), EntitlementScopeKind.USER, 43L)).isFalse();
        verifyNoInteractions(billingAccessRepository);
    }

    @Test
    @DisplayName("同一 TEAM の ADMIN は専用 repository query で許可する")
    void admin_isAllowed() {
        given(billingAccessRepository.existsAdmin(42L, EntitlementScopeKind.TEAM, 10L)).willReturn(true);

        assertThat(guard.canManage(authentication("ROLE_ADMIN"), EntitlementScopeKind.TEAM, 10L)).isTrue();

        verify(billingAccessRepository).existsAdmin(42L, EntitlementScopeKind.TEAM, 10L);
        verifyNoMoreInteractions(billingAccessRepository);
    }

    @Test
    @DisplayName("DEPUTY_ADMIN は同一 scope permission group の明示付与時だけ許可する")
    void deputy_explicitPermissionGroup_isAllowed() {
        given(billingAccessRepository.existsAdmin(42L, EntitlementScopeKind.ORG, 10L)).willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                42L, EntitlementScopeKind.ORG, 10L,
                "MANAGE_ORGANIZATION_BILLING")).willReturn(true);

        assertThat(guard.canManage(
                authentication("ROLE_DEPUTY_ADMIN"), EntitlementScopeKind.ORG, 10L)).isTrue();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は consumer API で短絡許可しない")
    void systemAdmin_isDenied() {
        given(billingAccessRepository.existsAdmin(42L, EntitlementScopeKind.TEAM, 10L)).willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                42L, EntitlementScopeKind.TEAM, 10L, "MANAGE_TEAM_BILLING")).willReturn(false);

        assertThat(guard.canManage(
                authentication("ROLE_SYSTEM_ADMIN"), EntitlementScopeKind.TEAM, 10L)).isFalse();
    }

    @Test
    @DisplayName("未認証・不正principal・null入力は repository を読まず拒否する")
    void invalidAuthentication_isDeniedWithoutQuery() {
        var unauthenticated = new UsernamePasswordAuthenticationToken("42", null);
        var malformed = new UsernamePasswordAuthenticationToken(
                "not-a-number", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(guard.canManage(null, EntitlementScopeKind.TEAM, 10L)).isFalse();
        assertThat(guard.canManage(unauthenticated, EntitlementScopeKind.TEAM, 10L)).isFalse();
        assertThat(guard.canManage(malformed, EntitlementScopeKind.TEAM, 10L)).isFalse();
        assertThat(guard.canManage(authentication("ROLE_ADMIN"), null, 10L)).isFalse();
        assertThat(guard.canManage(authentication("ROLE_ADMIN"), EntitlementScopeKind.TEAM, null)).isFalse();
        verifyNoInteractions(billingAccessRepository);
    }

    private UsernamePasswordAuthenticationToken authentication(String role) {
        return new UsernamePasswordAuthenticationToken("42", null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
