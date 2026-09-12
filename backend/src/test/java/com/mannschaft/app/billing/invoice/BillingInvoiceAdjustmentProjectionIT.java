package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 B: adjustments 投影（AC-28〜AC-33）。
 *
 * <p>返金・credit note・dispute は invoice lifecycle と混ぜない。
 * {@code billing_invoice_adjustments} へ PSP object ref UNIQUE の<b>不変複数行</b>として積み、
 * {@code billing_invoices.status} は上書きしない（設計書 05 §4）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: adjustments 投影 IT（AC-28〜33）")
class BillingInvoiceAdjustmentProjectionIT extends AbstractBillingInvoiceWebhookIT {

    private static final String INVOICE_REF = "in_adj_base";
    private static final String CHARGE_REF = "ch_adj_base";

    /** 各テストの前提となる PAID の invoice 投影を先に作る。 */
    private void seedPaidInvoice() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_adj", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_adj_seed", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject(INVOICE_REF, BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy",
                        10_000L, 500L, 950L, 10_450L, line)));
    }

    @Test
    @DisplayName("AC28: charge.refunded で kind=REFUND の不変行が追加され billing_invoices.status は上書きされない")
    void AC28_refundedでREFUND行が追加されinvoiceStatusは不変() throws Exception {
        seedPaidInvoice();

        postSigned(StripeWebhookPayloadFixture.event("evt_ac28_refund", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        CHARGE_REF, INVOICE_REF, 10_450L, 3_000L, "re_ac28", null)));

        assertThat(adjustmentsOf(INVOICE_REF))
                .extracting(BillingInvoiceAdjustmentEntity::getKind)
                .containsExactly("REFUND");
        assertThat(adjustmentsOf(INVOICE_REF).get(0).getAmount()).isEqualTo(3_000L);
        assertThat(adjustmentsOf(INVOICE_REF).get(0).getPspObjectRef()).isEqualTo("re_ac28");
        assertThat(requireInvoice(INVOICE_REF).getStatus())
                .as("返金で invoice の status を書き換えない").isEqualTo("PAID");
    }

    @Test
    @DisplayName("AC29: credit_note.created / voided で kind=CREDIT_NOTE が投影される")
    void AC29_creditNoteでCREDIT_NOTE行が投影される() throws Exception {
        seedPaidInvoice();

        postSigned(StripeWebhookPayloadFixture.event("evt_ac29_created", "credit_note.created",
                StripeWebhookPayloadFixture.creditNoteObject("cn_ac29", INVOICE_REF, 2_000L, "issued")));
        postSigned(StripeWebhookPayloadFixture.event("evt_ac29_voided", "credit_note.voided",
                StripeWebhookPayloadFixture.creditNoteObject("cn_ac29b", INVOICE_REF, 1_000L, "void")));

        assertThat(adjustmentsOf(INVOICE_REF))
                .extracting(BillingInvoiceAdjustmentEntity::getKind)
                .containsOnly("CREDIT_NOTE");
        assertThat(adjustmentsOf(INVOICE_REF))
                .extracting(BillingInvoiceAdjustmentEntity::getPspObjectRef)
                .containsExactlyInAnyOrder("cn_ac29", "cn_ac29b");
        assertThat(requireInvoice(INVOICE_REF).getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("AC30: charge.dispute.created / closed / funds_withdrawn / funds_reinstated で kind=DISPUTE が投影される")
    void AC30_disputeでDISPUTE行が投影される() throws Exception {
        seedPaidInvoice();
        // 返金経路で charge → invoice の対応を先に成立させる。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac30_seed_charge", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        CHARGE_REF, INVOICE_REF, 10_450L, 1L, "re_ac30_seed", null)));

        String[] disputeTypes = {
                "charge.dispute.created", "charge.dispute.funds_withdrawn",
                "charge.dispute.funds_reinstated", "charge.dispute.closed"};
        String[] statuses = {"needs_response", "under_review", "warning_closed", "won"};
        for (int i = 0; i < disputeTypes.length; i++) {
            postSigned(StripeWebhookPayloadFixture.event(
                    "evt_ac30_" + i, disputeTypes[i],
                    StripeWebhookPayloadFixture.disputeObjectWithExpandedCharge(
                            "dp_ac30_" + i, CHARGE_REF, INVOICE_REF, 10_450L, statuses[i])));
        }

        assertThat(adjustmentsOf(INVOICE_REF))
                .filteredOn(a -> "DISPUTE".equals(a.getKind()))
                .as("dispute の 4 イベントすべてが DISPUTE 行として投影される")
                .hasSize(disputeTypes.length);
    }

    @Test
    @DisplayName("AC31: 同一 psp_object_ref の再送で adjustments が重複しない")
    void AC31_同一pspObjectRefの再送で重複しない() throws Exception {
        seedPaidInvoice();
        String charge = StripeWebhookPayloadFixture.chargeObject(
                CHARGE_REF, INVOICE_REF, 10_450L, 3_000L, "re_ac31", null);

        postSigned(StripeWebhookPayloadFixture.event("evt_ac31_a", "charge.refunded", charge));
        // event id を変えた（Stripe の順不同再送を模した）2 度目。object ref は同じ。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac31_b", "charge.refunded", charge));

        assertThat(adjustmentsOf(INVOICE_REF))
                .as("psp_object_ref UNIQUE により 1 行だけ").hasSize(1);
    }

    @Test
    @DisplayName("AC32: refund の ownership を charge → invoice → billing_customer で二重照合する")
    void AC32_refundのownershipを二重照合する() throws Exception {
        seedPaidInvoice();

        // 当該 scope の invoice に属さない charge の返金は投影しない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac32_foreign", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        "ch_foreign", "in_not_ours", 5_000L, 5_000L, "re_ac32_foreign", null)));
        assertThat(adjustmentsOf(INVOICE_REF)).as("他 invoice の返金を混ぜない").isEmpty();

        // invoice 参照を持たない charge も投影しない（charge → invoice が辿れない）。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac32_noinvoice", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        "ch_noinvoice", null, 5_000L, 5_000L, "re_ac32_noinvoice", null)));
        assertThat(invoiceAdjustmentRepository.findByPspObjectRef("re_ac32_noinvoice"))
                .as("invoice を辿れない返金は投影しない").isEmpty();

        // 正当な返金だけが通る。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac32_ok", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        CHARGE_REF, INVOICE_REF, 10_450L, 1_000L, "re_ac32_ok", null)));
        assertThat(adjustmentsOf(INVOICE_REF))
                .extracting(BillingInvoiceAdjustmentEntity::getPspObjectRef)
                .containsExactly("re_ac32_ok");
    }

    @Test
    @DisplayName("AC33: Connect 由来の object（transfer_data 持ち）は adjustments へ投影しない")
    void AC33_Connect由来のobjectを投影しない() throws Exception {
        seedPaidInvoice();

        postSigned(StripeWebhookPayloadFixture.event("evt_ac33_connect", "charge.refunded",
                StripeWebhookPayloadFixture.chargeObject(
                        CHARGE_REF, INVOICE_REF, 10_450L, 3_000L, "re_ac33", "acct_connected_seller")));

        assertThat(invoiceAdjustmentRepository.findByPspObjectRef("re_ac33"))
                .as("Connect（transfer_data 持ち）の返金は billing の adjustments に入れない").isEmpty();
        assertThat(adjustmentsOf(INVOICE_REF)).isEmpty();
    }
}
