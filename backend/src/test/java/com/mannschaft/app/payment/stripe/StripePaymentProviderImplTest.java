package com.mannschaft.app.payment.stripe;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Charge;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link StripePaymentProviderImpl} の Webhook {@code data.object} 解決ロジック単体テスト。
 *
 * <p><b>背景（本番ブロッカー Bug B）:</b> stripe-java 28.2.0（API {@code 2025-02-24.acacia} 固定）に対し、
 * Stripe 口座の既定 API バージョンが basil 系（{@code 2025-03-31} 以降）だと
 * {@link EventDataObjectDeserializer#getObject()} がバージョン不一致で {@code Optional.empty()} を返し、
 * {@code payment_intent.*}/{@code charge.refunded}/{@code invoice.*}/{@code account.updated} が黙殺される
 * （実機で escrow が PENDING_CONFIRMATION→AUTHORIZED→CAPTURED へ昇格しないことを確認）。</p>
 *
 * <p>既存の {@code EscrowWebhookServiceTest} 等は provider 自体をモックしており {@code getObject()} を踏まないため、
 * provider 単体の raw-JSON フォールバックを直接検証する本テストが必要。Stripe の {@code retrieve} は
 * {@code mockStatic} で差し替える。</p>
 */
@DisplayName("StripePaymentProviderImpl data.object 解決（basil 黙殺フォールバック）")
class StripePaymentProviderImplTest {

    private StripePaymentProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new StripePaymentProviderImpl();
    }

    /**
     * getObject() が空のときに raw JSON から retrieve すべきオブジェクトを示す最小ペイロード。
     */
    private static String objectJson(String objectType, String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"" + objectType + "\"}";
    }

    @Nested
    @DisplayName("resolveStripeObject — getObject() 非空（acacia）の後方互換")
    class GetObjectPresent {

        @Test
        @DisplayName("getObject() が非空なら従来どおりそのオブジェクトを返し retrieve しない")
        void returnsExistingObjectWithoutRetrieve() {
            PaymentIntent pi = new PaymentIntent();
            pi.setId("pi_existing");
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.of(pi));

            try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isSameAs(pi);
                piStatic.verify(() -> PaymentIntent.retrieve("pi_existing"), never());
            }
        }
    }

    @Nested
    @DisplayName("resolveStripeObject — getObject() 空（basil）の raw JSON フォールバック")
    class GetObjectEmpty {

        @Test
        @DisplayName("payment_intent: rawJson の id で PaymentIntent.retrieve し実体を返す（escrow 本ブロッカー）")
        void retrievesPaymentIntent() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("payment_intent", "pi_basil_001"));

            PaymentIntent retrieved = new PaymentIntent();
            retrieved.setId("pi_basil_001");
            retrieved.setStatus("requires_capture");

            try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
                piStatic.when(() -> PaymentIntent.retrieve("pi_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(PaymentIntent.class);
                assertThat(((PaymentIntent) resolved).getId()).isEqualTo("pi_basil_001");
                assertThat(((PaymentIntent) resolved).getStatus()).isEqualTo("requires_capture");
                piStatic.verify(() -> PaymentIntent.retrieve("pi_basil_001"));
            }
        }

        @Test
        @DisplayName("charge: rawJson の id で Charge.retrieve し実体を返す（charge.refunded）")
        void retrievesCharge() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("charge", "ch_basil_001"));

            Charge retrieved = new Charge();
            retrieved.setId("ch_basil_001");
            retrieved.setPaymentIntent("pi_for_charge");

            try (MockedStatic<Charge> chargeStatic = mockStatic(Charge.class)) {
                chargeStatic.when(() -> Charge.retrieve("ch_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Charge.class);
                assertThat(((Charge) resolved).getId()).isEqualTo("ch_basil_001");
                assertThat(((Charge) resolved).getPaymentIntent()).isEqualTo("pi_for_charge");
            }
        }

        @Test
        @DisplayName("refund: rawJson の id で Refund.retrieve し実体を返す")
        void retrievesRefund() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("refund", "re_basil_001"));

            Refund retrieved = new Refund();
            retrieved.setId("re_basil_001");

            try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
                refundStatic.when(() -> Refund.retrieve("re_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Refund.class);
                assertThat(((Refund) resolved).getId()).isEqualTo("re_basil_001");
            }
        }

        @Test
        @DisplayName("checkout.session: rawJson の id で Session.retrieve し実体を返す")
        void retrievesSession() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("checkout.session", "cs_basil_001"));

            Session retrieved = new Session();
            retrieved.setId("cs_basil_001");

            try (MockedStatic<Session> sessionStatic = mockStatic(Session.class)) {
                sessionStatic.when(() -> Session.retrieve("cs_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Session.class);
                assertThat(((Session) resolved).getId()).isEqualTo("cs_basil_001");
            }
        }

        @Test
        @DisplayName("invoice: rawJson の id で Invoice.retrieve し実体を返す（継続課金）")
        void retrievesInvoice() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("invoice", "in_basil_001"));

            Invoice retrieved = new Invoice();
            retrieved.setId("in_basil_001");
            retrieved.setSubscription("sub_for_invoice");

            try (MockedStatic<Invoice> invoiceStatic = mockStatic(Invoice.class)) {
                invoiceStatic.when(() -> Invoice.retrieve("in_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Invoice.class);
                assertThat(((Invoice) resolved).getId()).isEqualTo("in_basil_001");
                assertThat(((Invoice) resolved).getSubscription()).isEqualTo("sub_for_invoice");
            }
        }

        @Test
        @DisplayName("subscription: rawJson の id で Subscription.retrieve し実体を返す（subscription.deleted）")
        void retrievesSubscription() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("subscription", "sub_basil_001"));

            Subscription retrieved = new Subscription();
            retrieved.setId("sub_basil_001");

            try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
                subStatic.when(() -> Subscription.retrieve("sub_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Subscription.class);
                assertThat(((Subscription) resolved).getId()).isEqualTo("sub_basil_001");
            }
        }

        @Test
        @DisplayName("account: rawJson の id で Account.retrieve し実体を返す（account.updated）")
        void retrievesAccount() throws StripeException {
            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            given(deserializer.getObject()).willReturn(Optional.empty());
            given(deserializer.getRawJson()).willReturn(objectJson("account", "acct_basil_001"));

            Account retrieved = new Account();
            retrieved.setId("acct_basil_001");

            try (MockedStatic<Account> accountStatic = mockStatic(Account.class)) {
                accountStatic.when(() -> Account.retrieve("acct_basil_001")).thenReturn(retrieved);

                StripeObject resolved = provider.resolveStripeObject(deserializer);

                assertThat(resolved).isInstanceOf(Account.class);
                assertThat(((Account) resolved).getId()).isEqualTo("acct_basil_001");
            }
        }
    }

    @Nested
    @DisplayName("resolveFromRawJson — 異常系（症状を隠さない）")
    class ResolveFromRawJsonEdge {

        @Test
        @DisplayName("未対応 object 型は null を返す（既存 if 連鎖が黙ってスキップ・例: application.deauthorized）")
        void unknownObjectTypeReturnsNull() {
            StripeObject resolved = provider.resolveFromRawJson(objectJson("application", "ca_001"));
            assertThat(resolved).isNull();
        }

        @Test
        @DisplayName("object 欠落は null を返す")
        void missingObjectReturnsNull() {
            StripeObject resolved = provider.resolveFromRawJson("{\"id\":\"pi_001\"}");
            assertThat(resolved).isNull();
        }

        @Test
        @DisplayName("id 欠落は null を返す")
        void missingIdReturnsNull() {
            StripeObject resolved = provider.resolveFromRawJson("{\"object\":\"payment_intent\"}");
            assertThat(resolved).isNull();
        }

        @Test
        @DisplayName("rawJson が null は null を返す")
        void nullRawJsonReturnsNull() {
            assertThat(provider.resolveFromRawJson(null)).isNull();
        }

        @Test
        @DisplayName("不正な JSON は症状を隠さず BusinessException(STRIPE_API_ERROR) を投げる")
        void malformedJsonThrows() {
            assertThatThrownBy(() -> provider.resolveFromRawJson("{not json"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ConnectPaymentErrorCode.STRIPE_API_ERROR);
        }

        @Test
        @DisplayName("retrieve が StripeException で失敗したら症状を隠さず BusinessException(STRIPE_API_ERROR) で上申")
        void retrieveFailureThrows() {
            StripeException stripeError = new ApiException("retrieve 失敗", "req_1", "code", 500, null);

            try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
                piStatic.when(() -> PaymentIntent.retrieve("pi_fail")).thenThrow(stripeError);

                assertThatThrownBy(() -> provider.resolveFromRawJson(objectJson("payment_intent", "pi_fail")))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(ConnectPaymentErrorCode.STRIPE_API_ERROR);
            }
        }
    }
}
