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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Codex 検分 <b>P1-1</b>: <b>撤回（RESUME）も</b> Stripe の metadata へ operationId を焼き付ける。
 *
 * <h2>何が壊れていたのか</h2>
 * <p>停止窓の回収（AC-77/AC-79）は「Stripe 側に<b>自分の</b> operation の痕跡があるか」だけを
 * 手掛かりに (b) と (c) を分ける。解約経路は
 * {@link StripeBillingPaymentGateway#cancelAtPeriodEnd(String, UUID)} が
 * {@code metadata.billingOperationId} を書いていたが、撤回経路は冪等キーしか渡しておらず、
 * Stripe 側には<b>直前の解約 operation の ID が残ったまま</b>だった。</p>
 *
 * <p>その結果、撤回が Stripe で成立した直後〜tx2 前にプロセスが停止すると、回収の痕跡照合は
 * 必ず外れる（残っているのは他人の ID）。判定は「痕跡なしの {@code CALLING_STRIPE}」となり
 * {@code RECONCILIATION_REQUIRED} へ倒れる——<b>正常に撤回済みの契約が永久隔離される</b>。
 * 回収の前提そのものが撤回経路では成立していなかった。</p>
 *
 * <p>測っているのは呼び出し回数ではなく<b>渡した metadata の中身</b>である。冪等キーは
 * {@code StripeBillingPaymentGatewayOperationKeyTest}（AC-5/AC-47）が別に固定している。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Codex P1-1: 撤回も metadata へ operationId を焼き付ける")
class StripeBillingPaymentGatewayResumeMetadataTest {

    private static final UUID OPERATION_ID =
            UUID.fromString("0199ab88-8888-8888-8888-888888888888");
    private static final String SUB_REF = "sub_pr6a_resume_meta";

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMethodService paymentMethodService;
    @InjectMocks private StripeBillingPaymentGateway gateway;

    @Test
    @DisplayName("P1-1: revertCancelAtPeriodEnd(ref, operationId) は metadata.billingOperationId を"
            + "自分の operationId へ更新する（直前の解約の ID を残さない）")
    void writesOwnOperationIdIntoSubscriptionMetadata() {
        given(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(
                eq(SUB_REF), anyString(), anyMap()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(SUB_REF, "active", 1_800_000_000L));

        gateway.revertCancelAtPeriodEnd(SUB_REF, OPERATION_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(stripePaymentProvider)
                .revertSubscriptionCancelAtPeriodEnd(eq(SUB_REF), anyString(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .as("撤回の痕跡を残さないと、撤回成功後に落ちた契約が永久検疫になる")
                .containsEntry(
                        BillingContractOperationRecoveryService.STRIPE_METADATA_OPERATION_ID_KEY,
                        OPERATION_ID.toString());
    }

    @Test
    @DisplayName("P1-1: 撤回の Saga 経路は metadata を持たない2引数版を使わない（痕跡が落ちるため）")
    void doesNotFallBackToMetadataLessCall() {
        given(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(
                eq(SUB_REF), anyString(), anyMap()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(SUB_REF, "active", 1_800_000_000L));

        gateway.revertCancelAtPeriodEnd(SUB_REF, OPERATION_ID);

        verify(stripePaymentProvider, never())
                .revertSubscriptionCancelAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("P1-1: 引継の差し戻しは2引数版のままで、既存呼び出し元の挙動を変えない（陽性対照）")
    void handoverRevertIsUnchanged() {
        UUID handoverRequestId = UUID.randomUUID();
        given(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(eq("sub_handover"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_handover", "active", 1_700_000_000L));

        gateway.revertCancelAtPeriodEndForHandover("sub_handover", handoverRequestId);

        verify(stripePaymentProvider).revertSubscriptionCancelAtPeriodEnd(eq("sub_handover"), anyString());
        verify(stripePaymentProvider, never())
                .revertSubscriptionCancelAtPeriodEnd(anyString(), anyString(), anyMap());
    }
}
