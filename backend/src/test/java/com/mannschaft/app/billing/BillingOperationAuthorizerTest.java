package com.mannschaft.app.billing;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("契約変更トランザクションの課金認可再確認")
class BillingOperationAuthorizerTest {

    @Mock private UserRowLockService userRowLockService;
    @Mock private BillingAccessRepository billingAccessRepository;

    @Test
    @DisplayName("TEAMは操作者行を先にロックしてから現在権限を照会する")
    void team_locksOperatorBeforeAuthorizationQuery() {
        given(billingAccessRepository.existsAdmin(10L, EntitlementScopeKind.TEAM, 20L))
                .willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                10L, EntitlementScopeKind.TEAM, 20L, "MANAGE_TEAM_BILLING"))
                .willReturn(true);

        assertThatCode(() -> authorizer().requireCanManage(
                10L, EntitlementScopeKind.TEAM, 20L)).doesNotThrowAnyException();

        InOrder order = inOrder(userRowLockService, billingAccessRepository);
        order.verify(userRowLockService).lock(10L);
        order.verify(billingAccessRepository)
                .lockAssignedPermissionGroups(10L, EntitlementScopeKind.TEAM, 20L);
        order.verify(billingAccessRepository).existsAdmin(10L, EntitlementScopeKind.TEAM, 20L);
        order.verify(billingAccessRepository).existsDeputyPermissionGroup(
                10L, EntitlementScopeKind.TEAM, 20L, "MANAGE_TEAM_BILLING");
    }

    @Test
    @DisplayName("ORGで現在権限がなければSYSTEM_ADMIN authorityに関係なく拒否する")
    void organization_withoutCurrentScopeGrant_isDenied() {
        given(billingAccessRepository.existsAdmin(10L, EntitlementScopeKind.ORG, 20L))
                .willReturn(false);
        given(billingAccessRepository.existsDeputyPermissionGroup(
                10L, EntitlementScopeKind.ORG, 20L, "MANAGE_ORGANIZATION_BILLING"))
                .willReturn(false);

        assertThatThrownBy(() -> authorizer().requireCanManage(
                10L, EntitlementScopeKind.ORG, 20L)).isInstanceOf(BusinessException.class);

        verify(userRowLockService).lock(10L);
    }

    @Test
    @DisplayName("USERは操作者本人だけを許可しrepositoryへ短絡しない")
    void user_allowsOnlySelfAfterLock() {
        assertThatCode(() -> authorizer().requireCanManage(
                10L, EntitlementScopeKind.USER, 10L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer().requireCanManage(
                10L, EntitlementScopeKind.USER, 11L)).isInstanceOf(BusinessException.class);

        verify(userRowLockService, org.mockito.Mockito.times(2)).lock(10L);
        verifyNoInteractions(billingAccessRepository);
    }

    private BillingOperationAuthorizer authorizer() {
        return new BillingOperationAuthorizer(userRowLockService, billingAccessRepository);
    }
}
