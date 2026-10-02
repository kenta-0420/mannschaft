package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentGateAccessServiceTest {

    @Mock private ContentGateResolverRegistry resolverRegistry;
    @Mock private ContentVisibilityChecker visibilityChecker;
    @Mock private AccessControlService accessControlService;
    @Mock private PaymentGateService paymentGateService;

    private ContentGateAccessService service;

    @BeforeEach
    void setUp() {
        service = new ContentGateAccessService(
                resolverRegistry, visibilityChecker, accessControlService, paymentGateService);
    }

    @Test
    void targetNotFoundIsSame404AndPaymentIsNotCalled() {
        when(resolverRegistry.resolveForAccess("POST", 10L)).thenReturn(Optional.empty());

        assertNotFound(() -> service.check("POST", 10L, 7L));
        verify(paymentGateService, never()).checkAccess(any(), any(), any(), any());
    }

    @Test
    void unsupportedTypeAndInvalidIdAreNotExistenceOracle() {
        assertNotFound(() -> service.check("FILE", 10L, 7L));
        assertNotFound(() -> service.check("POST", null, 7L));
        verifyNoAccessCalls();
    }

    @Test
    void f00DenyPrecedesPayment() {
        givenTarget("POST", 10L, new ContentGateTarget(10L, 3L, null));
        when(visibilityChecker.canView(ReferenceType.BLOG_POST, 10L, 7L)).thenReturn(false);

        assertNotFound(() -> service.check("POST", 10L, 7L));
        verify(paymentGateService, never()).checkAccess(any(), any(), any(), any());
    }

    @Test
    void fullAndLockedAreReturnedButHiddenIs404() {
        givenVisible("POST", 10L, 3L);
        when(paymentGateService.checkAccess(eq("POST"), eq(10L), eq(7L), any()))
                .thenReturn(new GateCheckResponse(true, false, List.of()));
        assertThat(service.check("POST", 10L, 7L).isAccessible()).isTrue();

        when(paymentGateService.checkAccess(eq("POST"), eq(10L), eq(7L), any()))
                .thenReturn(new GateCheckResponse(false, false,
                        List.of(new GateCheckResponse.RequiredItem(20L, "fee", null, false))));
        GateCheckResponse locked = service.check("POST", 10L, 7L);
        assertThat(locked.isAccessible()).isFalse();
        assertThat(locked.getRequiredItems()).hasSize(1);

        when(paymentGateService.checkAccess(eq("POST"), eq(10L), eq(7L), any()))
                .thenReturn(new GateCheckResponse(false, true, List.of()));
        assertNotFound(() -> service.check("POST", 10L, 7L));
    }

    @Test
    void onlyStrictScopeAdminAndSystemAdminBypassPayment() {
        givenVisible("POST", 10L, 3L);
        when(paymentGateService.checkAccess(eq("POST"), eq(10L), eq(7L), any()))
                .thenReturn(new GateCheckResponse(false, false, List.of()));
        when(accessControlService.getRoleName(7L, 3L, "TEAM")).thenReturn("DEPUTY_ADMIN");
        service.check("POST", 10L, 7L);
        verify(paymentGateService).checkAccess(eq("POST"), eq(10L), eq(7L), any());

        when(accessControlService.getRoleName(7L, 3L, "TEAM")).thenReturn("ADMIN");
        service.check("POST", 10L, 7L);
        verify(paymentGateService, org.mockito.Mockito.times(1))
                .checkAccess(eq("POST"), eq(10L), eq(7L), any());

        when(accessControlService.isSystemAdmin(8L)).thenReturn(true);
        service.check("POST", 10L, 8L);
        verify(paymentGateService, never()).checkAccess(eq("POST"), eq(10L), eq(8L), any());
    }

    @Test
    void organizationAdminUsesOrganizationScopeAndAuthorDoesNotBypass() {
        givenVisible("ANNOUNCEMENT", 10L, null, 9L);
        when(accessControlService.getRoleName(7L, 9L, "ORGANIZATION")).thenReturn("ADMIN");
        service.check("ANNOUNCEMENT", 10L, 7L);
        verify(paymentGateService, never()).checkAccess(eq("ANNOUNCEMENT"), eq(10L), eq(7L), any());
    }

    private void givenTarget(String type, Long id, ContentGateTarget target) {
        when(resolverRegistry.resolveForAccess(type, id)).thenReturn(Optional.of(target));
    }

    private void givenVisible(String type, Long id, Long teamId) {
        givenVisible(type, id, teamId, null);
    }

    private void givenVisible(String type, Long id, Long teamId, Long organizationId) {
        givenTarget(type, id, new ContentGateTarget(id, teamId, organizationId));
        ReferenceType referenceType = "POST".equals(type)
                ? ReferenceType.BLOG_POST : ReferenceType.ANNOUNCEMENT_FEED;
        when(visibilityChecker.canView(eq(referenceType), eq(id), any(Long.class))).thenReturn(true);
        when(accessControlService.isSystemAdmin(7L)).thenReturn(false);
    }

    private void assertNotFound(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.CONTENT_NOT_FOUND);
    }

    private void verifyNoAccessCalls() {
        verify(paymentGateService, never()).checkAccess(any(), any(), any(), any());
    }
}
