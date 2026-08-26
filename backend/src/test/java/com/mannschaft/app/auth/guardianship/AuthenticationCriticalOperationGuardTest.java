package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link AuthenticationCriticalOperationGuard} の単体テスト（F08.9 P3b）。
 * 後見切替セッション中（isProxy=true）の認証クリティカル操作を拒否し、
 * 本人操作（isProxy=false）は素通りすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationCriticalOperationGuard 単体テスト")
class AuthenticationCriticalOperationGuardTest {

    @Mock
    private ProxyInputContext proxyInputContext;

    @InjectMocks
    private AuthenticationCriticalOperationGuard guard;

    @Test
    @DisplayName("isProxy=true（後見切替中）→ MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION で拒否")
    void shouldThrowWhenActingAs() {
        given(proxyInputContext.isProxy()).willReturn(true);

        assertThatThrownBy(() -> guard.assertNotActingAs())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION);
    }

    @Test
    @DisplayName("isProxy=false（本人操作）→ 例外を投げず素通り")
    void shouldPassThroughWhenNotActingAs() {
        given(proxyInputContext.isProxy()).willReturn(false);

        assertThatCode(() -> guard.assertNotActingAs()).doesNotThrowAnyException();
    }
}
