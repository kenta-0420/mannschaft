package com.mannschaft.app.billing;

import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 試練A（第2隊）: AC-5 / AC-39 — operation Saga 経路の Stripe 呼び出しが
 * {@code billing-operation-{operationId}} を Idempotency-Key として<b>実際に渡す</b>ことを測る。
 *
 * <p>測っているのは「渡した文字列そのもの」（成果物）であり、呼び出し回数ではない。
 * {@code BillingOperationStateMachineTest} が測るのはキーの<b>生成規則</b>であり、
 * 規則が正しくても gateway が渡していなければ Stripe 側の冪等は成立しないため、
 * ここで gateway の口を独立に固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練A: AC-5 operation Saga の Stripe 冪等キー")
class StripeBillingPaymentGatewayOperationKeyTest {

    private static final UUID OPERATION_ID =
            UUID.fromString("0199ab02-3333-7444-8555-666677778888");

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMethodService paymentMethodService;
    @InjectMocks private StripeBillingPaymentGateway gateway;

    @Test
    @DisplayName("AC-5: cancelAtPeriodEnd(ref, operationId) は billing-operation-{operationId} を Stripe へ渡す")
    void passesOperationScopedIdempotencyKey() {
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_pr6a"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(
                        "sub_pr6a", "active", 1_800_000_000L));

        Instant periodEnd = gateway.cancelAtPeriodEnd("sub_pr6a", OPERATION_ID);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_pr6a"), keyCaptor.capture());
        assertThat(keyCaptor.getValue())
                .isEqualTo("billing-operation-0199ab02-3333-7444-8555-666677778888");
        assertThat(periodEnd).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
    }

    @Test
    @DisplayName("AC-5: 旧 cancelAtPeriodEnd(ref) は billing-cancel-{ref} のままで変わらない（既存呼び出し元の挙動を変えない・AC-39）")
    void legacyOverloadKeepsItsOwnNamespace() {
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_legacy"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(
                        "sub_legacy", "active", 1_700_000_000L));

        gateway.cancelAtPeriodEnd("sub_legacy");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_legacy"), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("billing-cancel-sub_legacy");
    }
}
