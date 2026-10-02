package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingAccessRepository;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** PR4: actorId 経路の scope 認可が Authentication 経路と同一判定を通ることを固定する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingCheckoutScopeGuard 認可契約")
class BillingCheckoutScopeGuardTest {

    private static final long ACTOR_ID = 42L;

    @Mock
    private BillingAccessRepository billingAccessRepository;

    private BillingCheckoutScopeGuard guard() {
        return new BillingCheckoutScopeGuard(new BillingAccessGuard(billingAccessRepository));
    }

    @Test
    @DisplayName("USER は本人の scope を許可し、他人の scope は repository を読まず 403 で拒否する")
    void userScope_本人だけ許可する() {
        assertThatCode(() -> guard().check(ACTOR_ID, EntitlementScopeKind.USER, ACTOR_ID))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> guard().check(ACTOR_ID, EntitlementScopeKind.USER, 43L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.SCOPE_FORBIDDEN);
        verifyNoInteractions(billingAccessRepository);
    }

    @Test
    @DisplayName("TEAM は ADMIN を許可する（Authentication 経路と同じ repository query を通る）")
    void teamAdmin_許可する() {
        given(billingAccessRepository.existsAdmin(ACTOR_ID, EntitlementScopeKind.TEAM, 10L))
                .willReturn(true);

        assertThatCode(() -> guard().check(ACTOR_ID, EntitlementScopeKind.TEAM, 10L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ORG は DEPUTY_ADMIN の明示付与を許可する")
    void orgDeputy_明示付与を許可する() {
        given(billingAccessRepository.existsAdmin(ACTOR_ID, EntitlementScopeKind.ORG, 10L))
                .willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                ACTOR_ID, EntitlementScopeKind.ORG, 10L, "MANAGE_ORGANIZATION_BILLING"))
                .willReturn(true);

        assertThatCode(() -> guard().check(ACTOR_ID, EntitlementScopeKind.ORG, 10L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ADMIN でも権限付与でもない TEAM 操作は 403 で fail-closed に拒否する")
    void teamNoRole_拒否する() {
        given(billingAccessRepository.existsAdmin(ACTOR_ID, EntitlementScopeKind.TEAM, 10L))
                .willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                ACTOR_ID, EntitlementScopeKind.TEAM, 10L, "MANAGE_TEAM_BILLING"))
                .willReturn(false);

        assertThatThrownBy(() -> guard().check(ACTOR_ID, EntitlementScopeKind.TEAM, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.SCOPE_FORBIDDEN);
    }
}
