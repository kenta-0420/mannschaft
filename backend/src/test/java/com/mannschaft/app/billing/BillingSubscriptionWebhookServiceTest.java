package com.mannschaft.app.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.invoice.BillingInvoiceProjectionService;
import com.mannschaft.app.billing.invoice.BillingWebhookEventGate;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.payment.stripe.StripePaymentProvider.BillingSubscriptionWebhookEventInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F20.1 実決済: {@link BillingSubscriptionWebhookService} 単体テスト（試練）。
 *
 * <p>対象 AC:</p>
 * <ul>
 *   <li>AC-33: {@code checkout.session.completed}（billingContractId あり）到達で activatePaidContract</li>
 *   <li>AC-34: 同一 event_id 再送は {@link WebhookIdempotencyService} でスキップ（handler 不実行）・失敗は FAILED＋再送出</li>
 *   <li>AC-37: {@code invoice.payment_failed} → markContractPastDue／{@code invoice.paid} → extendContractPeriod</li>
 *   <li>AC-38: billing に無い subscriptionId は {@code false}（membership へフォールバック）・
 *       billingContractId なしの checkout.session.* も {@code false}（F09.13/F08.2 既存処理へ）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingSubscriptionWebhookService 単体テスト（billing所有判定＋冪等ゲート＋状態遷移委譲）")
class BillingSubscriptionWebhookServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
    private static final long PERIOD_END_EPOCH = Instant.parse("2026-08-10T00:00:00Z").getEpochSecond();
    private static final LocalDateTime PERIOD_END_LDT =
            LocalDateTime.ofInstant(Instant.ofEpochSecond(PERIOD_END_EPOCH), ZoneOffset.UTC);

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private WebhookIdempotencyService idempotencyService;
    @Mock private BillingContractService billingContractService;
    @Mock private BillingContractRepository billingContractRepository;
    /**
     * F20.1 PR5: invoice 投影。本テストの payload はダミー文字列（"p"）なので
     * {@code readInvoice} は空を返し、投影は行われない。ここで測るのは所有判定と契約遷移の委譲である。
     */
    @Mock private BillingInvoiceProjectionService invoiceProjectionService;

    private BillingSubscriptionWebhookService service;

    @BeforeEach
    void setUp() {
        // ゲートは本物を使う（冪等ゲートの呼ばれ方まで含めて契約を測るため）。
        StripeBillingPayloadParser parser = new StripeBillingPayloadParser(new ObjectMapper());
        BillingWebhookEventGate gate = new BillingWebhookEventGate(idempotencyService, parser);
        service = new BillingSubscriptionWebhookService(
                stripePaymentProvider, idempotencyService, billingContractService,
                billingContractRepository, invoiceProjectionService, gate, parser, FIXED_CLOCK);
    }

    private BillingSubscriptionWebhookEventInfo event(
            String type, String billingContractId, String subscriptionId, Long periodEnd) {
        return new BillingSubscriptionWebhookEventInfo(
                "evt_1", type, false, "cs_1", billingContractId, subscriptionId, "cus_1", periodEnd);
    }

    private BillingContractEntity billingContract() {
        BillingContractEntity c = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(9L)
                .contractKind(ContractKind.PLAN).planKey("FULL")
                .status(ContractStatus.ACTIVE).priceJpySnapshot(2000)
                .pspSubscriptionRef("sub_bill").build();
        c.setId(UUID.randomUUID());
        return c;
    }

    // ============================================================
    // AC-33: checkout.session.completed
    // ============================================================

    @Test
    @DisplayName("AC-33: completed（billingContractId あり）は冪等ゲート→activatePaidContract→PROCESSED 確定")
    void ac33_checkoutCompleted_activates() {
        UUID contractId = UUID.randomUUID();
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("checkout.session.completed", contractId.toString(), "sub_bill", PERIOD_END_EPOCH));
        given(idempotencyService.tryBegin("evt_1", "checkout.session.completed", false)).willReturn(true);

        boolean handled = service.handleCheckoutCompletedIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).activatePaidContract(
                eq(contractId), eq("cus_1"), eq("sub_bill"), eq(PERIOD_END_LDT));
        verify(idempotencyService).markProcessed("evt_1", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC-38: completed（billingContractId なし）は false（F09.13/F08.2 既存処理へ・billing は関与しない）")
    void ac38_checkoutCompleted_notBilling_returnsFalse() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("checkout.session.completed", null, null, null));

        boolean handled = service.handleCheckoutCompletedIfBilling("p", "s");

        assertThat(handled).isFalse();
        verifyNoInteractions(idempotencyService, billingContractService);
    }

    @Test
    @DisplayName("AC-47: checkout.session.expired（billingContractId あり）は abandonPendingContract（PENDING→CANCELLED・スロット解放で再挑戦可）")
    void ac47_checkoutExpired_abandons() {
        UUID contractId = UUID.randomUUID();
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("checkout.session.expired", contractId.toString(), null, null));
        given(idempotencyService.tryBegin("evt_1", "checkout.session.expired", false)).willReturn(true);

        boolean handled = service.handleCheckoutExpiredIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).abandonPendingContract(contractId);
        verify(idempotencyService).markProcessed("evt_1", WebhookProcessStatus.PROCESSED);
    }

    // ============================================================
    // AC-34: 冪等（event_id 再送）＋失敗の FAILED 記録
    // ============================================================

    @Test
    @DisplayName("AC-34: 同一 event_id の再送（確定済み）は handler を実行しない（二重発行ゼロの第一層）")
    void ac34_duplicateEvent_skipsHandler() {
        UUID contractId = UUID.randomUUID();
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("checkout.session.completed", contractId.toString(), "sub_bill", PERIOD_END_EPOCH));
        given(idempotencyService.tryBegin("evt_1", "checkout.session.completed", false)).willReturn(false);

        boolean handled = service.handleCheckoutCompletedIfBilling("p", "s");

        // billing 所有イベントなので true（membership へフォールバックさせない）が、handler は実行されない。
        assertThat(handled).isTrue();
        verify(billingContractService, never()).activatePaidContract(any(), any(), any(), any());
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("AC-34: handler 失敗は FAILED 記録＋再送出（握り潰さない・Stripe 再送でリカバリ）")
    void ac34_handlerFailure_marksFailedAndRethrows() {
        UUID contractId = UUID.randomUUID();
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("checkout.session.completed", contractId.toString(), "sub_bill", PERIOD_END_EPOCH));
        given(idempotencyService.tryBegin("evt_1", "checkout.session.completed", false)).willReturn(true);
        given(billingContractService.activatePaidContract(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.handleCheckoutCompletedIfBilling("p", "s"))
                .isInstanceOf(IllegalStateException.class);

        verify(idempotencyService).markFailed("evt_1");
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    // ============================================================
    // AC-37 / AC-38: invoice.* / customer.subscription.deleted
    // ============================================================

    @Test
    @DisplayName("AC-38: billing に無い subscriptionId は false（membership へフォールバック・冪等ゲートも消費しない）")
    void ac38_unknownSubscription_returnsFalse() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("invoice.paid", null, "sub_membership", PERIOD_END_EPOCH));
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_membership"))
                .willReturn(Optional.empty());

        boolean handled = service.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isFalse();
        // ★冪等ゲートを消費しない（membership 側が自分の event_id ゲートを通せるように）。
        verifyNoInteractions(idempotencyService, billingContractService);
    }

    @Test
    @DisplayName("AC-37: invoice.paid（billing 所有）は extendContractPeriod（期末延長・PAST_DUE 回復）")
    void ac37_invoicePaid_extends() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("invoice.paid", null, "sub_bill", PERIOD_END_EPOCH));
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_bill"))
                .willReturn(Optional.of(billingContract()));
        given(idempotencyService.tryBegin(eq("evt_1"), eq("invoice.paid"), eq(false),
                any(), any(), any(), any())).willReturn(true);

        boolean handled = service.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).extendContractPeriod("sub_bill", PERIOD_END_LDT);
        verify(idempotencyService).markProcessed("evt_1", WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC-37: invoice.payment_failed（billing 所有）は markContractPastDue（権利は触らない）")
    void ac37_paymentFailed_marksPastDue() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("invoice.payment_failed", null, "sub_bill", null));
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_bill"))
                .willReturn(Optional.of(billingContract()));
        given(idempotencyService.tryBegin(eq("evt_1"), eq("invoice.payment_failed"), eq(false),
                any(), any(), any(), any())).willReturn(true);

        boolean handled = service.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).markContractPastDue("sub_bill");
    }

    @Test
    @DisplayName("AC-35: customer.subscription.deleted（billing 所有）は expireSubscriptionContract")
    void ac35_subscriptionDeleted_expires() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("customer.subscription.deleted", null, "sub_bill", PERIOD_END_EPOCH));
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_bill"))
                .willReturn(Optional.of(billingContract()));
        given(idempotencyService.tryBegin("evt_1", "customer.subscription.deleted", false)).willReturn(true);

        boolean handled = service.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).expireSubscriptionContract("sub_bill", PERIOD_END_LDT);
    }

    @Test
    @DisplayName("AC-38 補: invoice.created 等の未対応 billing イベントは IGNORED で確定（true・membership に流さない）")
    void ac38_unsupportedBillingEvent_ignored() {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s"))
                .willReturn(event("invoice.created", null, "sub_bill", null));
        given(billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull("sub_bill"))
                .willReturn(Optional.of(billingContract()));
        given(idempotencyService.tryBegin(eq("evt_1"), eq("invoice.created"), eq(false),
                any(), any(), any(), any())).willReturn(true);

        boolean handled = service.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(idempotencyService).markProcessed("evt_1", WebhookProcessStatus.IGNORED);
        verifyNoInteractions(billingContractService);
    }
}
