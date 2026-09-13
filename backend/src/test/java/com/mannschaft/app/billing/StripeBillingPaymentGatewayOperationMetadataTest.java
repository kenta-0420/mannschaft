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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 試練D（第4b隊）: <b>AC-77</b> — Stripe の subscription metadata に operationId が
 * <b>実際に保存され、読み戻せる</b>ことを測る。
 *
 * <h2>なぜこれが G群の土台なのか</h2>
 * <p>D1（tx1 commit → Stripe 呼び出し → tx2 反映）は、Stripe 呼び出しの成否が DB に
 * 書かれないままプロセスが落ちる窓を構造的に作る（D8 の (a)(b)(c)）。回収はそのとき
 * 「Stripe 側に自分の operation の痕跡があるか」を唯一の手掛かりとするため、
 * <b>metadata が保存されなければ回収は原理的に成立しない</b>。</p>
 *
 * <p>現行の {@code StripeBillingPaymentGateway#cancelAtPeriodEnd(String)} は
 * {@code cancel_at_period_end=true} しか送らず metadata を書かない
 * （{@code StripePaymentProviderImpl#cancelSubscriptionAtPeriodEnd} / 本 gateway を実読して確認）。
 * したがって本テストは「書いた値」と「読み戻した値」の両方を成果物として固定する。</p>
 *
 * <p>測っているのは呼び出し回数ではなく<b>渡した metadata の中身</b>である。冪等キーそのものは
 * {@code StripeBillingPaymentGatewayOperationKeyTest}（試練A・AC-5）が別に固定している。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("試練D: AC-77 Stripe metadata への operationId 焼き付けと読み戻し")
class StripeBillingPaymentGatewayOperationMetadataTest {

    private static final UUID OPERATION_ID =
            UUID.fromString("0199ab77-7777-7777-8777-777777777777");
    private static final String SUB_REF = "sub_pr6a_meta";

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMethodService paymentMethodService;
    @InjectMocks private StripeBillingPaymentGateway gateway;

    @Test
    @DisplayName("AC-77: cancelAtPeriodEnd(ref, operationId) は metadata.billingOperationId に operationId を焼き付ける")
    void writesOperationIdIntoSubscriptionMetadata() {
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(SUB_REF), anyString(), anyMap()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(SUB_REF, "active", 1_800_000_000L));

        gateway.cancelAtPeriodEnd(SUB_REF, OPERATION_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(stripePaymentProvider)
                .cancelSubscriptionAtPeriodEnd(eq(SUB_REF), anyString(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .as("metadata に operationId が載らなければ停止窓(a)(b)(c) は回収できない")
                .containsEntry(
                        BillingContractOperationRecoveryService.STRIPE_METADATA_OPERATION_ID_KEY,
                        OPERATION_ID.toString());
    }

    @Test
    @DisplayName("AC-77: operation Saga 経路は metadata を持たない2引数版の Stripe 呼び出しを使わない（metadata が落ちるため）")
    void doesNotFallBackToMetadataLessCall() {
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq(SUB_REF), anyString(), anyMap()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo(SUB_REF, "active", 1_800_000_000L));

        gateway.cancelAtPeriodEnd(SUB_REF, OPERATION_ID);

        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("AC-77: 旧 cancelAtPeriodEnd(ref) は metadata を書かない2引数版のままで、既存呼び出し元の挙動を変えない（陽性対照）")
    void legacyOverloadIsUnchanged() {
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_legacy"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_legacy", "active", 1_700_000_000L));

        gateway.cancelAtPeriodEnd("sub_legacy");

        verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_legacy"), anyString());
        verify(stripePaymentProvider, never())
                .cancelSubscriptionAtPeriodEnd(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("AC-77: findOperationIdOnSubscription は Stripe 実物の metadata から operationId を読み戻す")
    void readsOperationIdBackFromStripe() {
        given(stripePaymentProvider.retrieveSubscriptionDetail(SUB_REF))
                .willReturn(new StripePaymentProvider.SubscriptionDetail(
                        SUB_REF, "active", true, 1_790_000_000L, 1_800_000_000L, null,
                        Map.of(BillingContractOperationRecoveryService.STRIPE_METADATA_OPERATION_ID_KEY,
                                OPERATION_ID.toString())));

        Optional<UUID> found = gateway.findOperationIdOnSubscription(SUB_REF);

        assertThat(found).contains(OPERATION_ID);
    }

    @Test
    @DisplayName("AC-77: metadata に operationId が無い subscription は空を返す（停止窓(a) の判定材料・陰性対照）")
    void returnsEmptyWhenNoTrace() {
        given(stripePaymentProvider.retrieveSubscriptionDetail(SUB_REF))
                .willReturn(new StripePaymentProvider.SubscriptionDetail(
                        SUB_REF, "active", false, 1_790_000_000L, 1_800_000_000L, null,
                        Map.of("handoverRequestId", UUID.randomUUID().toString())));

        assertThat(gateway.findOperationIdOnSubscription(SUB_REF))
                .as("他ドメインの metadata を operationId と誤読してはならない")
                .isEmpty();
    }
}
