package com.mannschaft.app.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.invoice.BillingInvoiceOwner;
import com.mannschaft.app.billing.invoice.BillingInvoiceProjectionService;
import com.mannschaft.app.billing.invoice.BillingWebhookEventGate;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.payment.stripe.StripePaymentProvider.BillingSubscriptionWebhookEventInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Billing Center PR6b-1 — Codex 検分 P1×2 の修繕検体（第15隊）。
 *
 * <h2>P1-1: 完了済み change が通常更新 invoice を横取りする</h2>
 * <p>upgrade 完了後も Stripe Subscription の {@code metadata.billingOperationId} は残り続けるため、
 * {@code resolveViaSubscription} が終端 change を除外していないと、<b>以後のあらゆる通常更新
 * invoice</b> がその終端 change に解決され、{@code extendContractPeriod}（期間延長）と
 * {@code markContractPastDue}（PAST_DUE 遷移）が恒久的にスキップされる。</p>
 *
 * <h2>P1-2: applied が paid より先に着くと確定できないまま固まる</h2>
 * <p>{@code customer.subscription.pending_update_applied} が {@code invoice.paid} より先に届くと
 * items 照合の前提（invoice.paid 済み）が満たせず確定できない。Stripe は applied を再発行しないため、
 * 後続の {@code invoice.paid} が items を<b>再照合して確定する</b>のでなければ change は
 * {@code REQUIRES_ACTION} のまま固まり、pointer が後続のあらゆる操作を遮断する。</p>
 *
 * <p>Docker 不要の純 UT。確定サービスと webhook サービスは<b>実体</b>を組み立て、外周
 * （Repository / Stripe ゲートウェイ / Saga）だけを Mockito で差し替える。確定の分岐そのものを
 * 測るため、確定サービスをモックに置き換えてはならない。</p>
 */
@DisplayName("PR6b-1 修繕: 終端 change の横取り（P1-1）と applied→paid 逆順の確定（P1-2）")
class BillingPlanChangeConfirmationOrderingTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
    private static final long PERIOD_END_EPOCH = Instant.parse("2026-10-17T00:00:00Z").getEpochSecond();
    private static final String SUBSCRIPTION_REF = "sub_pr6b1";
    private static final long ACTOR_ID = 42L;

    private BillingContractChangeRepository changeRepository;
    private BillingContractRepository contractRepository;
    private BillingContractOperationSagaService sagaService;
    private BillingPaymentGateway billingPaymentGateway;
    private BillingInvoiceJpaRepository invoiceRepository;
    private AuditLogService auditLogService;
    private BillingContractService billingContractService;
    private WebhookIdempotencyService idempotencyService;
    private BillingInvoiceProjectionService invoiceProjectionService;
    private StripePaymentProvider stripePaymentProvider;
    private BillingContractRepository webhookContractRepository;

    private BillingSubscriptionWebhookService webhookService;

    @BeforeEach
    void setUp() {
        changeRepository = mock(BillingContractChangeRepository.class);
        contractRepository = mock(BillingContractRepository.class);
        sagaService = mock(BillingContractOperationSagaService.class);
        billingPaymentGateway = mock(BillingPaymentGateway.class);
        invoiceRepository = mock(BillingInvoiceJpaRepository.class);
        auditLogService = mock(AuditLogService.class);
        billingContractService = mock(BillingContractService.class);
        idempotencyService = mock(WebhookIdempotencyService.class);
        invoiceProjectionService = mock(BillingInvoiceProjectionService.class);
        stripePaymentProvider = mock(StripePaymentProvider.class);
        webhookContractRepository = mock(BillingContractRepository.class);

        StripeBillingPayloadParser parser = new StripeBillingPayloadParser(new ObjectMapper());
        BillingPlanChangeConfirmationService confirmationService =
                new BillingPlanChangeConfirmationService(
                        changeRepository, contractRepository, sagaService, billingPaymentGateway,
                        invoiceRepository, parser, auditLogService);
        BillingWebhookEventGate gate = new BillingWebhookEventGate(idempotencyService, parser);
        webhookService = new BillingSubscriptionWebhookService(
                stripePaymentProvider, idempotencyService, billingContractService,
                webhookContractRepository, invoiceProjectionService, gate, parser,
                mock(BillingPayerHandoverService.class),
                mock(BillingContractOperationRecoveryService.class),
                confirmationService, FIXED_CLOCK);

        // Saga は PR6a 資産。ここでは「reflection を1回実行して結果を返す」ことだけを模す。
        given(sagaService.applyAndFinalize(any(UUID.class), any())).willAnswer(invocation -> {
            java.util.function.Supplier<?> reflection = invocation.getArgument(1);
            return reflection.get();
        });
        given(idempotencyService.tryBegin(anyString(), anyString(), anyBoolean(), anyString(),
                any(), any(), any())).willReturn(true);
        given(idempotencyService.tryBegin(anyString(), anyString(), anyBoolean())).willReturn(true);
        // billing 所有（psp_subscription_ref 逆引きヒット）。
        given(webhookContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(SUBSCRIPTION_REF))
                .willReturn(Optional.of(contractFixture(UUID.randomUUID())));
    }

    // ════════ P1-1: 終端 change は通常更新 invoice を横取りしない ════════

    @Test
    @DisplayName("P1-1: upgrade 完了後の通常更新 invoice.paid は期間延長される（終端 change に横取りされない）")
    void terminalChangeDoesNotHijackRenewalPaid() {
        givenTerminalChangeLingersOnSubscription();
        givenBillingInvoice("in_renewal", "invoice.paid");

        boolean handled = webhookService.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).extendContractPeriod(
                SUBSCRIPTION_REF,
                java.time.LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(PERIOD_END_EPOCH), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("P1-1: upgrade 完了後の通常更新 invoice.payment_failed は PAST_DUE へ遷移する")
    void terminalChangeDoesNotHijackRenewalPaymentFailed() {
        givenTerminalChangeLingersOnSubscription();
        givenBillingInvoice("in_renewal", "invoice.payment_failed");

        boolean handled = webhookService.handleSubscriptionEventIfBilling("p", "s");

        assertThat(handled).isTrue();
        verify(billingContractService).markContractPastDue(SUBSCRIPTION_REF);
    }

    // ════════ P1-2: applied → paid の逆順でも確定する ════════

    @Test
    @DisplayName("P1-2: applied が paid より先に着いても、paid 到着時に items を再照合して APPLIED へ確定する")
    void appliedBeforePaidStillConfirmsOnPaid() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.REQUIRES_ACTION);
        change.setPendingUpdateExpiresAt(Instant.parse("2026-09-17T06:00:00Z"));
        change.setPendingUpdateTargetSnapshot("{\"items\":[{\"price\":\"price_full\"}]}");
        BillingContractEntity contract = contractFixture(contractId);

        given(billingPaymentGateway.findOperationIdOnSubscription(SUBSCRIPTION_REF))
                .willReturn(Optional.of(change.getOperationId()));
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(change.getOperationId()))
                .willReturn(Optional.of(change));
        given(changeRepository.saveAndFlush(any(BillingContractChangeEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(contractRepository.findByIdAndDeletedAtIsNull(contractId))
                .willReturn(Optional.of(contract));
        // paid 到着の時点では Stripe 側の items は既に target へ切り替わっている
        // （applied が先着したのだから当然である）。
        given(billingPaymentGateway.retrieveSubscription(SUBSCRIPTION_REF)).willReturn(
                new BillingPaymentGateway.SubscriptionSnapshot(
                        SUBSCRIPTION_REF, "active", false, null, null, null,
                        List.of(new BillingPaymentGateway.SubscriptionItemSnapshot(
                                "si_1", "price_full", 1L)),
                        null));

        // ① applied が先着する（この時点では invoice.paid 未確認なので確定できない）。
        givenSubscriptionEvent(BillingSubscriptionWebhookService.SUBSCRIPTION_PENDING_UPDATE_APPLIED,
                change.getOperationId());
        webhookService.handleSubscriptionEventIfBilling("p", "s");
        assertThat(change.getStatus())
                .as("applied 単独では確定しない（invoice.paid が確定の唯一の根拠）")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);

        // ② 続いて invoice.paid が着く。Stripe は applied を再発行しないので、ここで
        //    items を再照合して確定できなければ change は永久に REQUIRES_ACTION のまま固まる。
        givenBillingInvoice("in_upgrade", "invoice.paid");
        webhookService.handleSubscriptionEventIfBilling("p", "s");

        assertThat(change.getStatus())
                .as("paid 到着時に items を再照合して確定する")
                .isEqualTo(BillingContractChangeStatus.APPLIED);
        assertThat(contract.getPlanKey()).as("権利が target プランへ切り替わる").isEqualTo("FULL");
        verify(billingContractService, never()).extendContractPeriod(anyString(), any());
    }

    @Test
    @DisplayName("P1-2: items がまだ切り替わっていない paid では確定しない（単調性・確定の主体は webhook のみ）")
    void paidWithoutItemSwitchDoesNotConfirm() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.REQUIRES_ACTION);
        change.setPendingUpdateExpiresAt(Instant.parse("2026-09-17T06:00:00Z"));
        change.setPendingUpdateTargetSnapshot("{\"items\":[{\"price\":\"price_full\"}]}");

        given(billingPaymentGateway.findOperationIdOnSubscription(SUBSCRIPTION_REF))
                .willReturn(Optional.of(change.getOperationId()));
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(change.getOperationId()))
                .willReturn(Optional.of(change));
        given(changeRepository.saveAndFlush(any(BillingContractChangeEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(billingPaymentGateway.retrieveSubscription(SUBSCRIPTION_REF)).willReturn(
                new BillingPaymentGateway.SubscriptionSnapshot(
                        SUBSCRIPTION_REF, "active", false, null, null, null,
                        List.of(new BillingPaymentGateway.SubscriptionItemSnapshot(
                                "si_1", "price_basic", 1L)),
                        null));

        givenBillingInvoice("in_upgrade", "invoice.paid");
        webhookService.handleSubscriptionEventIfBilling("p", "s");

        assertThat(change.getStatus())
                .as("items が未切替なら確定せず pending_update_applied を待つ")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }

    // ════════ フィクスチャ ════════

    /** upgrade 完了後も Stripe Subscription に metadata.billingOperationId が残っている状態。 */
    private void givenTerminalChangeLingersOnSubscription() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity terminal = changeFixture(
                contractId, BillingContractChangeStatus.APPLIED);
        given(billingPaymentGateway.findOperationIdOnSubscription(SUBSCRIPTION_REF))
                .willReturn(Optional.of(terminal.getOperationId()));
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(terminal.getOperationId()))
                .willReturn(Optional.of(terminal));
        // 通常更新 invoice は change に bind されていない。
        given(changeRepository.findByStripeInvoiceRefAndDeletedAtIsNull(anyString()))
                .willReturn(Optional.empty());
    }

    private void givenBillingInvoice(String invoiceRef, String type) {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s")).willReturn(
                new BillingSubscriptionWebhookEventInfo(
                        "evt_" + type + "_" + invoiceRef, type, false, null, null,
                        SUBSCRIPTION_REF, "cus_1", PERIOD_END_EPOCH, null));
        InvoiceView view = new InvoiceView(invoiceRef, "cus_1", SUBSCRIPTION_REF, "paid",
                "subscription_cycle", "jpy", 1000L, 0L, 0L, 1000L, null, null,
                null, null, null, List.of(), true);
        given(invoiceProjectionService.readInvoice("p")).willReturn(Optional.of(view));
        given(invoiceProjectionService.resolveOwner(view)).willReturn(Optional.of(
                new BillingInvoiceOwner(UUID.randomUUID(), UUID.randomUUID(),
                        EntitlementScopeKind.USER, ACTOR_ID, null)));
        BillingInvoiceEntity paidInvoice = mock(BillingInvoiceEntity.class);
        given(paidInvoice.getPaidAt()).willReturn(Instant.parse("2026-09-17T00:00:00Z"));
        given(invoiceRepository.findByPspInvoiceRef(invoiceRef)).willReturn(Optional.of(paidInvoice));
    }

    private void givenSubscriptionEvent(String type, UUID operationId) {
        given(stripePaymentProvider.constructBillingSubscriptionEvent("p", "s")).willReturn(
                new BillingSubscriptionWebhookEventInfo(
                        "evt_" + type, type, false, null, null,
                        SUBSCRIPTION_REF, "cus_1", PERIOD_END_EPOCH, operationId.toString()));
    }

    private BillingContractChangeEntity changeFixture(UUID contractId, BillingContractChangeStatus status) {
        return BillingContractChangeEntity.builder()
                .operationId(UUID.randomUUID())
                .contractId(contractId)
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingContractChangeKind.UPGRADE)
                .status(status)
                .fromPlanKey("BASIC")
                .toPlanKey("FULL")
                .fromPriceBandVersionId(UUID.randomUUID())
                .toPriceBandVersionId(UUID.randomUUID())
                .fromAmountIncludingTax(1_200L)
                .toAmountIncludingTax(3_300L)
                .stripeSubscriptionRef(SUBSCRIPTION_REF)
                .effectiveAt(Instant.parse("2026-09-17T00:00:00Z"))
                .idempotencyKey(UUID.randomUUID().toString())
                .requestHash("0".repeat(64))
                .version(0L)
                .createdBy(ACTOR_ID)
                .build();
    }

    private BillingContractEntity contractFixture(UUID contractId) {
        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(ACTOR_ID)
                .contractKind(ContractKind.PLAN).planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1_200)
                .createdBy(ACTOR_ID).payerUserId(ACTOR_ID)
                .version(0L)
                .build();
        contract.setId(contractId);
        return contract;
    }
}
