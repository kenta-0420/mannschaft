package com.mannschaft.app.billing;

import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;

/**
 * 柱③-B PR-2 請求支払者の引継: {@link StripeBillingPaymentGateway} の引継系メソッドの単体試験。
 *
 * <p>設計書 {@code docs/architecture/billing_payer_handover_design.md} §3.4（冪等キーの名前空間）・
 * §3.2（回復経路）・§3.6（二段検証）に対応する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeBillingPaymentGateway 引継（柱③-B PR-2）")
class StripeBillingPaymentGatewayHandoverTest {

    @Mock
    private StripePaymentProvider stripePaymentProvider;

    @Mock
    private PaymentMethodService paymentMethodService;

    @InjectMocks
    private StripeBillingPaymentGateway gateway;

    private static final UUID HANDOVER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID NEW_CONTRACT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OLD_CONTRACT_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Long NEW_PAYER_USER_ID = 4242L;

    @Nested
    @DisplayName("冪等キーの名前空間（設計書 §3.4・AC-24）")
    class IdempotencyKeys {

        @Test
        @DisplayName("新サブスク作成は billing-handover-create-{handoverRequestId} を渡す")
        void createUsesHandoverCreateKey() {
            given(paymentMethodService.getOrCreateStripeCustomerId(NEW_PAYER_USER_ID)).willReturn("cus_new");
            given(stripePaymentProvider.createBillingHandoverSubscriptionCheckoutSession(
                    anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                    anyLong(), anyString(), anyString(), anyString()))
                    .willReturn(new StripePaymentProvider.CheckoutSessionInfo("cs_1", "https://example/1", null));

            gateway.createHandoverSubscriptionCheckout(
                    NEW_PAYER_USER_ID, 1200, "プラン", NEW_CONTRACT_ID, OLD_CONTRACT_ID, HANDOVER_ID,
                    Instant.ofEpochSecond(1_800_000_000L), "https://ok", "https://ng");

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(stripePaymentProvider).createBillingHandoverSubscriptionCheckoutSession(
                    eq("cus_new"), eq(1200L), eq("プラン"), eq(NEW_CONTRACT_ID.toString()),
                    eq(HANDOVER_ID.toString()), eq(OLD_CONTRACT_ID.toString()),
                    eq(1_800_000_000L), eq("https://ok"), eq("https://ng"), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo("billing-handover-create-" + HANDOVER_ID);
        }

        @Test
        @DisplayName("旧サブスクの期末解約予約は billing-handover-schedule-cancel-{id}（通常解約 billing-cancel-* と別名前空間）")
        void scheduleCancelUsesDedicatedKey() {
            given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_old"), anyString()))
                    .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_old", "active", 1_700_000_000L));

            Instant periodEnd = gateway.scheduleCancelAtPeriodEndForHandover("sub_old", HANDOVER_ID);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_old"), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo("billing-handover-schedule-cancel-" + HANDOVER_ID);
            assertThat(keyCaptor.getValue()).doesNotStartWith("billing-cancel-");
            assertThat(periodEnd).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        }

        @Test
        @DisplayName("旧サブスクの差し戻しは billing-handover-revert-cancel-{id}")
        void revertUsesRevertKey() {
            given(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(eq("sub_old"), anyString()))
                    .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_old", "active", 1_700_000_000L));

            gateway.revertCancelAtPeriodEndForHandover("sub_old", HANDOVER_ID);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(stripePaymentProvider).revertSubscriptionCancelAtPeriodEnd(eq("sub_old"), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo("billing-handover-revert-cancel-" + HANDOVER_ID);
        }

        @Test
        @DisplayName("新 trial サブスクの即時解約は billing-handover-cancel-new-{id}")
        void cancelNewUsesCancelNewKey() {
            gateway.cancelHandoverNewSubscription("sub_new", HANDOVER_ID);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(stripePaymentProvider).cancelBillingSubscriptionImmediately(eq("sub_new"), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo("billing-handover-cancel-new-" + HANDOVER_ID);
        }
    }

    @Test
    @DisplayName("createHandoverSubscriptionCheckout は trialEnd を unix 秒でプロバイダへ渡す（AC-4/AC-5）")
    void trialEndIsPassedAsEpochSeconds() {
        Instant trialEnd = Instant.parse("2026-10-01T00:00:00Z");
        given(paymentMethodService.getOrCreateStripeCustomerId(NEW_PAYER_USER_ID)).willReturn("cus_new");
        given(stripePaymentProvider.createBillingHandoverSubscriptionCheckoutSession(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StripePaymentProvider.CheckoutSessionInfo("cs_9", "https://example/9", null));

        BillingPaymentGateway.CheckoutSessionInfo info = gateway.createHandoverSubscriptionCheckout(
                NEW_PAYER_USER_ID, 980, "月謝", NEW_CONTRACT_ID, OLD_CONTRACT_ID, HANDOVER_ID,
                trialEnd, "https://ok", "https://ng");

        ArgumentCaptor<Long> trialCaptor = ArgumentCaptor.forClass(Long.class);
        verify(stripePaymentProvider).createBillingHandoverSubscriptionCheckoutSession(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                trialCaptor.capture(), anyString(), anyString(), anyString());
        assertThat(trialCaptor.getValue()).isEqualTo(trialEnd.getEpochSecond());
        assertThat(info.sessionId()).isEqualTo("cs_9");
        assertThat(info.url()).isEqualTo("https://example/9");
    }

    @Nested
    @DisplayName("findHandoverSubscriptionRef（回復経路・設計書 §3.2）")
    class FindHandoverSubscriptionRef {

        @Test
        @DisplayName("metadata.handoverRequestId が一致する1件を返す")
        void returnsMatchingSubscription() {
            given(paymentMethodService.getOrCreateStripeCustomerId(NEW_PAYER_USER_ID)).willReturn("cus_new");
            given(stripePaymentProvider.listSubscriptionsByCustomer("cus_new")).willReturn(List.of(
                    detail("sub_other", "active", Map.of("handoverRequestId", UUID.randomUUID().toString())),
                    detail("sub_target", "trialing", Map.of("handoverRequestId", HANDOVER_ID.toString()))));

            Optional<String> found = gateway.findHandoverSubscriptionRef(NEW_PAYER_USER_ID, HANDOVER_ID);

            assertThat(found).contains("sub_target");
        }

        @Test
        @DisplayName("一致する metadata が無ければ空を返す")
        void returnsEmptyWhenNoMatch() {
            given(paymentMethodService.getOrCreateStripeCustomerId(NEW_PAYER_USER_ID)).willReturn("cus_new");
            given(stripePaymentProvider.listSubscriptionsByCustomer("cus_new")).willReturn(List.of(
                    detail("sub_other", "active", Map.of("handoverRequestId", UUID.randomUUID().toString())),
                    detail("sub_nometa", "active", Map.of())));

            assertThat(gateway.findHandoverSubscriptionRef(NEW_PAYER_USER_ID, HANDOVER_ID)).isEmpty();
        }

        @Test
        @DisplayName("metadata が一致していても canceled / incomplete_expired は回収対象から除外する")
        void excludesDeadSubscriptions() {
            given(paymentMethodService.getOrCreateStripeCustomerId(NEW_PAYER_USER_ID)).willReturn("cus_new");
            given(stripePaymentProvider.listSubscriptionsByCustomer("cus_new")).willReturn(List.of(
                    detail("sub_dead", "canceled", Map.of("handoverRequestId", HANDOVER_ID.toString())),
                    detail("sub_expired", "incomplete_expired", Map.of("handoverRequestId", HANDOVER_ID.toString()))));

            assertThat(gateway.findHandoverSubscriptionRef(NEW_PAYER_USER_ID, HANDOVER_ID)).isEmpty();
        }

        private StripePaymentProvider.SubscriptionDetail detail(String id, String status, Map<String, String> metadata) {
            return new StripePaymentProvider.SubscriptionDetail(id, status, false, null, null, null, metadata);
        }
    }

    @Nested
    @DisplayName("hasUsablePaymentMethod（二段検証1段目・設計書 §3.6）")
    class HasUsablePaymentMethod {

        @Test
        @DisplayName("既定 PaymentMethod 未登録なら false（外部 HTTP を呼ばず DB 参照のみ）")
        void falseWhenNoPaymentMethod() {
            given(paymentMethodService.hasDefaultPaymentMethod(NEW_PAYER_USER_ID)).willReturn(false);

            assertThat(gateway.hasUsablePaymentMethod(NEW_PAYER_USER_ID)).isFalse();
            then(stripePaymentProvider).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("既定 PaymentMethod 登録済みなら true")
        void trueWhenPaymentMethodRegistered() {
            given(paymentMethodService.hasDefaultPaymentMethod(NEW_PAYER_USER_ID)).willReturn(true);

            assertThat(gateway.hasUsablePaymentMethod(NEW_PAYER_USER_ID)).isTrue();
            then(stripePaymentProvider).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("retrieveSubscription（設計書 §3.6.1）")
    class RetrieveSubscription {

        @Test
        @DisplayName("unix 秒を Instant へ変換し、pending_setup_intent 有りを hasPendingSetupIntent()=true とする")
        void convertsEpochSecondsAndDetectsPendingSetupIntent() {
            given(stripePaymentProvider.retrieveSubscriptionDetail("sub_x")).willReturn(
                    new StripePaymentProvider.SubscriptionDetail(
                            "sub_x", "trialing", true, 1_700_000_000L, 1_702_000_000L, "seti_1", Map.of()));

            BillingPaymentGateway.SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_x");

            assertThat(snapshot.subscriptionRef()).isEqualTo("sub_x");
            assertThat(snapshot.status()).isEqualTo("trialing");
            assertThat(snapshot.cancelAtPeriodEnd()).isTrue();
            assertThat(snapshot.currentPeriodStart()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
            assertThat(snapshot.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1_702_000_000L));
            assertThat(snapshot.hasPendingSetupIntent()).isTrue();
        }

        @Test
        @DisplayName("期間が null なら null のまま、pending_setup_intent 無しなら hasPendingSetupIntent()=false")
        void nullPeriodsStayNull() {
            given(stripePaymentProvider.retrieveSubscriptionDetail("sub_y")).willReturn(
                    new StripePaymentProvider.SubscriptionDetail(
                            "sub_y", "active", false, null, null, null, Map.of()));

            BillingPaymentGateway.SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_y");

            assertThat(snapshot.currentPeriodStart()).isNull();
            assertThat(snapshot.currentPeriodEnd()).isNull();
            assertThat(snapshot.hasPendingSetupIntent()).isFalse();
        }
    }
}
