package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.payment.WebhookProcessStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 A2/A3: 既存イベントの回帰防止と webhook 共通基盤（AC-15〜AC-25, AC-27）。
 *
 * <p>PR5 は共通 dispatcher に手を入れるため、既存の契約遷移（PAST_DUE / 回復 / EXPIRED）が
 * <b>invoice 投影の追加後も同一イベントで一体に成立する</b>ことをここで固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: webhook dispatcher 契約 IT（AC-15〜25, 27）")
class BillingWebhookDispatcherContractIT extends AbstractBillingInvoiceWebhookIT {

    /** PR5 では扱わない（受信するが確定させない）イベント種別。 */
    private static final String[] PENDING_EVENT_TYPES = {
            "invoice.payment_action_required",
            "customer.subscription.updated",
            "customer.subscription.pending_update_applied",
            "customer.subscription.pending_update_expired",
            "subscription_schedule.updated",
    };

    private String invoiceOf(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 10_000L, 500L, 950L, 10_450L, line);
    }

    private ContractStatus contractStatus() {
        return billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId)
                .orElseThrow().getStatus();
    }

    // ───────────── A2. 既存イベントの回帰防止 ─────────────

    @Test
    @DisplayName("AC15: invoice.payment_failed で ACTIVE → PAST_DUE になる")
    void AC15_paymentFailedでPAST_DUEになる() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac15_failed", "invoice.payment_failed", invoiceOf("in_ac15", "open")));

        assertThat(contractStatus()).isEqualTo(ContractStatus.PAST_DUE);
    }

    @Test
    @DisplayName("AC16: PAST_DUE 後の invoice.paid で ACTIVE へ回復し契約期間が延長される")
    void AC16_paidでPAST_DUEから回復し期間延長される() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac16_failed", "invoice.payment_failed", invoiceOf("in_ac16a", "open")));
        assertThat(contractStatus()).isEqualTo(ContractStatus.PAST_DUE);

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac16_paid", "invoice.paid", invoiceOf("in_ac16b", "paid")));

        BillingContractEntity contract = billingContractRepository
                .findByIdAndDeletedAtIsNull(billingContractId).orElseThrow();
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(contract.getCurrentPeriodEnd())
                .as("invoice の period_end（2026-02-01）まで延長される")
                .isEqualTo(java.time.LocalDateTime.of(2026, 2, 1, 0, 0));
    }

    @Test
    @DisplayName("AC17: customer.subscription.deleted で EXPIRED・active pointer 削除される")
    void AC17_subscriptionDeletedでEXPIREDとpointer削除() throws Exception {
        activeContractPointerRepository.saveAndFlush(
                com.mannschaft.app.billing.ActiveContractPointerEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER)
                        .scopeId(BILLING_SCOPE_ID)
                        .contractKind(ContractKind.PLAN)
                        .addonFeatureKey("")
                        .contractId(billingContractId)
                        .createdAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0))
                        .updatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0))
                        .build());

        postSigned(StripeWebhookPayloadFixture.event("evt_ac17_deleted", "customer.subscription.deleted",
                StripeWebhookPayloadFixture.subscriptionObject(
                        BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, "canceled", 1_769_904_000L)));

        assertThat(contractStatus().name()).as("EXPIRED になる").isEqualTo("EXPIRED");
        assertThat(activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        EntitlementScopeKind.USER, BILLING_SCOPE_ID, ContractKind.PLAN, ""))
                .as("active pointer が削除される").isEmpty();
    }

    @Test
    @DisplayName("AC18: invoice.paid の1イベントで契約遷移と invoice 投影が一体に成立する")
    void AC18_契約遷移と投影が同一イベントで一体に成立する() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac18_failed", "invoice.payment_failed", invoiceOf("in_ac18a", "open")));
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac18_paid", "invoice.paid", invoiceOf("in_ac18b", "paid")));

        assertThat(contractStatus()).as("契約は回復している").isEqualTo(ContractStatus.ACTIVE);
        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac18a"))
                .as("payment_failed の invoice も投影される").isPresent();
        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac18b"))
                .as("paid の invoice も投影される").isPresent();
        assertThat(requireInvoice("in_ac18b").getStatus()).isEqualTo("PAID");
    }

    // ───────────── A3. webhook 共通基盤 ─────────────

    @Test
    @DisplayName("AC19: V196 追加6列（billing_contract_id / billing_customer_id / stripe_object_ref / payload_sha256 / attempt_count）に実際に書き込む")
    void AC19_V196追加列に実際に書き込む() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac19", "invoice.paid", invoiceOf("in_ac19", "paid")));

        var event = webhookEvent("evt_ac19").orElseThrow(() ->
                new AssertionError("stripe_webhook_events に受信記録が無い"));
        assertThat(event.getBillingContractId()).as("billing_contract_id").isEqualTo(billingContractId);
        assertThat(event.getBillingCustomerId()).as("billing_customer_id").isEqualTo(billingCustomerId);
        assertThat(event.getStripeObjectRef()).as("stripe_object_ref").isEqualTo("in_ac19");
        assertThat(event.getPayloadSha256()).as("payload_sha256").isNotNull().matches("[0-9a-f]{64}");
        assertThat(event.getAttemptCount()).as("attempt_count は初期化されている").isNotNull();
    }

    @Test
    @DisplayName("AC20: invoice.paid の1イベントで invoice 投影と契約期間延長が一体に成否する")
    void AC20_投影と期間延長が一体に成否する() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac20", "invoice.paid", invoiceOf("in_ac20", "paid")));

        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac20")).isPresent();
        assertThat(billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId)
                .orElseThrow().getCurrentPeriodEnd())
                .isEqualTo(java.time.LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(webhookEvent("evt_ac20").orElseThrow().getProcessStatus())
                .isEqualTo(WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC22: PR5 で扱わないイベントは 200 を返しつつ process_status=RECEIVED のまま確定させない")
    void AC22_未対応イベントはRECEIVEDのまま確定しない() throws Exception {
        for (String type : PENDING_EVENT_TYPES) {
            String eventId = "evt_ac22_" + type.replace('.', '_');
            String object = type.startsWith("invoice.")
                    ? invoiceOf("in_ac22_" + type.replace('.', '_'), "open")
                    : StripeWebhookPayloadFixture.subscriptionObject(
                    BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, "active", 1_769_904_000L);

            int status = postSigned(StripeWebhookPayloadFixture.event(eventId, type, object))
                    .getResponse().getStatus();

            assertThat(status).as("%s は 200", type).isEqualTo(200);
            assertThat(webhookEvent(eventId))
                    .as("%s の受信記録が残る", type).isPresent();
            assertThat(webhookEvent(eventId).orElseThrow().getProcessStatus())
                    .as("%s は RECEIVED のまま（PROCESSED/IGNORED にしない）", type)
                    .isEqualTo(WebhookProcessStatus.RECEIVED);
        }
    }

    @Test
    @DisplayName("AC23: RECEIVED の意味をイベント種別で判別できる（新規列を追加しない）")
    void AC23_RECEIVEDの意味を種別で判別できる() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event("evt_ac23_pending",
                "customer.subscription.updated",
                StripeWebhookPayloadFixture.subscriptionObject(
                        BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, "active", 1_769_904_000L)));

        var event = webhookEvent("evt_ac23_pending").orElseThrow(() ->
                new AssertionError("stripe_webhook_events に受信記録が無い"));
        assertThat(event.getType())
                .as("既存の type 列だけで保留理由を判別できる").isEqualTo("customer.subscription.updated");
        assertThat(event.getProcessStatus()).isEqualTo(WebhookProcessStatus.RECEIVED);

        // 保留の意味づけのために列を足していないこと。
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'stripe_webhook_events'
                """, String.class);
        assertThat(columns).as("保留理由の新規列を追加しない")
                .doesNotContain("pending_reason", "deferred_reason", "hold_reason", "pending_kind");
    }

    @Test
    @DisplayName("AC24: 保留イベントの滞留件数を数える運用クエリで件数が取れる")
    void AC24_保留イベントの滞留件数が数えられる() throws Exception {
        for (String type : PENDING_EVENT_TYPES) {
            String object = type.startsWith("invoice.")
                    ? invoiceOf("in_ac24_" + type.replace('.', '_'), "open")
                    : StripeWebhookPayloadFixture.subscriptionObject(
                    BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, "active", 1_769_904_000L);
            postSigned(StripeWebhookPayloadFixture.event(
                    "evt_ac24_" + type.replace('.', '_'), type, object));
        }

        assertThat(countPendingReceivedEvents(PENDING_EVENT_TYPES))
                .as("保留 5 種すべてが RECEIVED で滞留している件数を数えられる")
                .isEqualTo(PENDING_EVENT_TYPES.length);
    }

    @Test
    @DisplayName("AC25: invoice の所有判定は subscription ref 単独ではなく invoice.customer と scope-owned Customer の照合を併用する")
    void AC25_所有判定はcustomer照合を併用する() throws Exception {
        // subscription ref は billing のものだが customer が別人 → 所有と断定してはならない。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac25", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac25_mismatch", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac25", "cus_someone_else",
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy", 10_000L, 500L, 950L, 10_450L, line)));

        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac25"))
                .as("customer が一致しない invoice を scope 所有として投影しない").isEmpty();
        assertThat(webhookEvent("evt_ac25_mismatch").map(e -> e.getProcessStatus()).orElse(null))
                .as("PROCESSED として確定させない").isNotEqualTo(WebhookProcessStatus.PROCESSED);

        // 陽性対照: customer も一致する双子は投影されること。これが無いと「未実装で常に空」でも
        // 緑になり、二重照合を検証したことにならない。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac25_match", "invoice.paid", invoiceOf("in_ac25ok", "paid")));
        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac25ok"))
                .as("陽性対照: customer も subscription も一致する invoice は投影される").isPresent();
    }

    @Test
    @DisplayName("AC27: 共通 dispatcher の所有判定順序は Connect/escrow → Billing → F08.9 会費に固定される")
    void AC27_所有判定順序がConnect_Billing_会費の順である() throws Exception {
        // (1) Connect/escrow 由来（payment_intent.*）を billing が横取りしない。
        String pi = """
                {"id":"pi_ac27_escrow","object":"payment_intent","amount":1000,"amount_received":1000,
                 "currency":"jpy","status":"succeeded","metadata":{}}""";
        int escrowStatus = postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac27_escrow", "payment_intent.succeeded", pi)).getResponse().getStatus();
        assertThat(escrowStatus).isEqualTo(200);
        assertThat(invoiceRepository.count()).as("escrow イベントで billing 投影を作らない").isZero();

        // (2) Billing 所有の invoice は billing が処理し、F08.9 まで落ちない。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac27_billing", "invoice.paid", invoiceOf("in_ac27", "paid")));
        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac27"))
                .as("billing 所有は billing が処理する").isPresent();

        // (3) 所有外は F08.9 会費側へ落ち、billing 投影を作らない。
        String foreignLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac27f", "会費", 1L, 1_000L, 0L, 0L, false, null);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac27_foreign", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac27f", "cus_f089_membership",
                        FOREIGN_SUBSCRIPTION_REF, "paid", "jpy", 1_000L, 0L, 0L, 1_000L, foreignLine)));
        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac27f")).isEmpty();
    }
}
