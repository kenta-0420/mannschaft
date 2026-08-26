package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractService.ContractResult;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F20.1 実決済: {@link BillingCheckoutService} 単体テスト（試練）。
 *
 * <p>AC-32（PENDING 起票→Checkout URL 返却）と、Stripe 失敗時の補償
 * （PENDING 放棄＋{@code ENTITLEMENT_015} 502・孤児 PENDING を残さない）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingCheckoutService 単体テスト（PENDING起票→Checkout生成→補償）")
class BillingCheckoutServiceTest {

    @Mock private BillingContractService billingContractService;
    @Mock private BillingPaymentGateway billingPaymentGateway;

    @InjectMocks private BillingCheckoutService service;

    private final UUID contractId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:3000");
    }

    private ContractResult pendingResult() {
        return new ContractResult(contractId, EntitlementScopeKind.USER, 9L,
                ContractKind.PLAN, "FULL", null, ContractStatus.PENDING, null, null, 2000,
                LocalDateTime.now(), null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("AC-32: PENDING 起票→Checkout 生成→URL 返却（success/cancel は app.base-url の /billing/plans）")
    void ac32_startPaidContract_returnsCheckoutUrl() {
        given(billingContractService.createPendingPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L))
                .willReturn(pendingResult());
        given(billingPaymentGateway.createSubscriptionCheckout(
                eq(9L), eq(2000), anyString(), eq(contractId),
                eq("http://localhost:3000/billing/plans?checkout=success"),
                eq("http://localhost:3000/billing/plans?checkout=cancelled")))
                .willReturn(new BillingPaymentGateway.CheckoutSessionInfo("cs_1", "https://checkout.stripe.com/c/cs_1"));

        BillingCheckoutService.PaidCheckoutResult result = service.startPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L);

        assertThat(result.pending().status()).isEqualTo(ContractStatus.PENDING);
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/cs_1");
    }

    @Test
    @DisplayName("AC-32 補償: Stripe 失敗時は PENDING 放棄（abandon）＋ENTITLEMENT_015（502）・孤児 PENDING を残さない")
    void ac32_stripeFailure_compensatesAndThrows015() {
        given(billingContractService.createPendingPaidContract(
                any(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong()))
                .willReturn(pendingResult());
        given(billingPaymentGateway.createSubscriptionCheckout(any(), anyInt(), anyString(), any(), any(), any()))
                .willThrow(new IllegalStateException("stripe down"));

        assertThatThrownBy(() -> service.startPaidContract(
                EntitlementScopeKind.USER, 9L, null, ContractKind.PLAN, "FULL", null, 2000, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CHECKOUT_SESSION_FAILED);

        // 補償: PENDING 契約を放棄しスロットを解放（再挑戦可能に）。
        verify(billingContractService).abandonPendingContract(contractId);
    }
}
