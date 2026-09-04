package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.payment.WebhookProcessStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 A: invoice 投影（AC-1〜AC-14）。
 *
 * <p>実 {@code StripeWebhookController} に実署名で検体を流し、{@code billing_invoices} /
 * {@code billing_invoice_lines} / {@code stripe_webhook_events} の実 DB 行だけを観測する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: invoice 投影 webhook IT（AC-1〜14）")
class BillingInvoiceProjectionWebhookIT extends AbstractBillingInvoiceWebhookIT {

    /** 税抜の基本検体: 単価1,000円×数量10・税率10%・割引500円 → 税抜9,500 / 税額950 / 税込10,450。 */
    private String standardInvoice(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 10_000L, 500L, 950L, 10_450L, line);
    }

    @Test
    @DisplayName("AC1: invoice.finalized で billing_invoices に1行投影され psp_invoice_ref が一意キーになる")
    void AC1_finalizedで投影される() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac1_finalized", "invoice.finalized", standardInvoice("in_ac1", "open"));

        postSigned(payload);

        BillingInvoiceEntity invoice = requireInvoice("in_ac1");
        assertThat(invoice.getBillingCustomerId()).as("scope 所有 Customer に紐づく").isEqualTo(billingCustomerId);
        assertThat(invoice.getContractId()).as("契約に紐づく").isEqualTo(billingContractId);
        assertThat(invoice.getScopeId()).isEqualTo(BILLING_SCOPE_ID);
        assertThat(invoice.getStatus()).isEqualTo("OPEN");
        assertThat(invoice.getFinalizedAt()).as("finalized_at が入る").isNotNull();
        assertThat(invoiceRepository.count()).as("同一 invoice の投影は 1 行だけ").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC2: invoice.paid で status=PAID と paid_at が入る")
    void AC2_paidでPAIDとpaidAtが入る() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac2_finalized", "invoice.finalized", standardInvoice("in_ac2", "open")));
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac2_paid", "invoice.paid", standardInvoice("in_ac2", "paid")));

        BillingInvoiceEntity invoice = requireInvoice("in_ac2");
        assertThat(invoice.getStatus()).isEqualTo("PAID");
        assertThat(invoice.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("AC3: invoice.voided で status=VOID と voided_at が入る")
    void AC3_voidedでVOIDとvoidedAtが入る() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac3_finalized", "invoice.finalized", standardInvoice("in_ac3", "open")));
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac3_voided", "invoice.voided", standardInvoice("in_ac3", "void")));

        BillingInvoiceEntity invoice = requireInvoice("in_ac3");
        assertThat(invoice.getStatus()).isEqualTo("VOID");
        assertThat(invoice.getVoidedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC4: 同一 event.id の再送は副作用ゼロで 200 を返す")
    void AC4_同一eventIdの再送は副作用ゼロ() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac4_paid", "invoice.paid", standardInvoice("in_ac4", "paid"));

        postSigned(payload);
        BillingInvoiceEntity first = requireInvoice("in_ac4");

        int status = postSigned(payload).getResponse().getStatus();

        assertThat(status).as("再送は 200").isEqualTo(200);
        assertThat(invoiceRepository.count()).as("invoice 行は増えない").isEqualTo(1L);
        assertThat(linesOf("in_ac4")).as("line 行も増えない").hasSize(1);
        assertThat(requireInvoice("in_ac4").getUpdatedAt())
                .as("再送で投影が書き換わらない").isEqualTo(first.getUpdatedAt());
    }

    @Test
    @DisplayName("AC5: subtotal - discount + tax != total なら投影を確定しない（fail-closed）")
    void AC5_金額恒等式が破れたら投影しない() throws Exception {
        // 10000 - 500 + 950 = 10450 であるべきところに total=99999 を入れた壊れた検体。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac5", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        String payload = StripeWebhookPayloadFixture.event("evt_ac5_broken", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac5", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 500L, 950L, 99_999L, line));

        postSigned(payload);

        assertThat(invoiceOf("in_ac5")).as("恒等式が破れた invoice は投影しない").isEmpty();
    }

    @Test
    @DisplayName("AC6: JPY の line amount を再丸めせず Stripe の値のまま保存する")
    void AC6_JPY金額を再丸めしない() throws Exception {
        // 端数の出る金額をそのまま保存すること（1円単位の再計算・四捨五入をしない）。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac6", "BASIC プラン", 3L, 3_333L, 0L, 333L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac6", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac6", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        3_333L, 0L, 333L, 3_666L, line)));

        BillingInvoiceLineEntity stored = linesOf("in_ac6").get(0);
        assertThat(stored.getAmountExcludingTax()).as("Stripe の line amount そのまま").isEqualTo(3_333L);
        assertThat(stored.getTaxAmount()).isEqualTo(333L);
        assertThat(stored.getAmountIncludingTax()).isEqualTo(3_666L);
    }

    @Test
    @DisplayName("AC7: billing 所有でない invoice は event id を確定させず F08.9 会費側へ fallthrough し最終 200")
    void AC7_billing非所有はeventId未確定でfallthrough() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac7", "会費", 1L, 1_000L, 0L, 0L, false, null);
        String payload = StripeWebhookPayloadFixture.event("evt_ac7_foreign", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac7", "cus_f089_membership",
                        FOREIGN_SUBSCRIPTION_REF, "paid", "jpy", 1_000L, 0L, 0L, 1_000L, line));

        int status = postSigned(payload).getResponse().getStatus();

        assertThat(status).as("最終的に 200").isEqualTo(200);
        assertThat(invoiceOf("in_ac7")).as("billing の投影を作らない").isEmpty();
        assertThat(webhookEvent("evt_ac7_foreign")
                .map(e -> e.getProcessStatus() == WebhookProcessStatus.PROCESSED
                        || e.getProcessStatus() == WebhookProcessStatus.IGNORED)
                .orElse(false))
                .as("billing 側が event id を確定させない（所有外は未消費で fallthrough）")
                .isFalse();
        assertThat(webhookEvent("evt_ac7_foreign").map(e -> e.getBillingContractId()).orElse(null))
                .as("billing 所有として紐付けない").isNull();
    }

    @Test
    @DisplayName("AC9: event.created が投影より古ければ巻き戻さない（単調更新）")
    void AC9_古いeventで投影を巻き戻さない() throws Exception {
        long newer = System.currentTimeMillis() / 1000L;
        long older = newer - 3_600L;

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac9_paid", "invoice.paid", standardInvoice("in_ac9", "paid"), newer));
        assertThat(requireInvoice("in_ac9").getStatus()).isEqualTo("PAID");

        // 遅れて届いた「古い」open 状態のイベント。単調更新なら PAID を OPEN へ戻さない。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac9_stale_open", "invoice.finalized", standardInvoice("in_ac9", "open"), older));

        assertThat(requireInvoice("in_ac9").getStatus())
                .as("古い event で PAID を OPEN に巻き戻さない").isEqualTo("PAID");
        assertThat(requireInvoice("in_ac9").getPaidAt()).as("paid_at も消さない").isNotNull();
    }

    @Test
    @DisplayName("AC11: 署名不正は 400 を返し投影も event 記録も作らない")
    void AC11_署名不正は400() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac11_badsig", "invoice.paid", standardInvoice("in_ac11", "paid"));

        int status = postWithSignature(payload, "t=1,v1=deadbeef").getResponse().getStatus();

        assertThat(status).as("署名不正は 400").isEqualTo(400);
        assertThat(invoiceOf("in_ac11")).isEmpty();
        assertThat(webhookEvent("evt_ac11_badsig")).isEmpty();
    }

    @Test
    @DisplayName("AC12: raw payload を DB に永続化せず payload_sha256 だけを残す")
    void AC12_rawPayloadを永続化しない() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac12", "invoice.paid", standardInvoice("in_ac12", "paid"));

        postSigned(payload);

        assertThat(webhookEvent("evt_ac12")).as("受信記録は残る").isPresent();
        assertThat(webhookEvent("evt_ac12").orElseThrow().getPayloadSha256())
                .as("payload_sha256 が 64 桁 hex で入る")
                .isNotNull()
                .matches("[0-9a-f]{64}");

        // どの列にも payload の本文が入っていないこと（列名ではなく実データを走査して確かめる）。
        List<String> rowDump = jdbcTemplate.query(
                "SELECT CONCAT_WS('|', event_id, type, IFNULL(stripe_object_ref,''), "
                        + "IFNULL(payload_sha256,''), process_status) FROM stripe_webhook_events",
                (rs, i) -> rs.getString(1));
        assertThat(rowDump).noneMatch(row -> row.contains("\"object\":\"invoice\""));
        assertThat(rowDump).noneMatch(row -> row.contains("billing-taro@example.com"));
    }

    @Test
    @DisplayName("AC13: 失敗時に attempt_count / failed_at を更新し 5 回超過で FAILED 確定する（新テーブルを作らない）")
    void AC13_失敗時にattemptCountとfailedAtを更新する() throws Exception {
        // scope-owned Customer と照合できない（＝billing 所有だが処理不能な）検体を作るため、
        // subscription は billing のものだが customer が別人という不整合検体を使う。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac13", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        String payload = StripeWebhookPayloadFixture.event("evt_ac13", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac13", "cus_someone_else",
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy", 10_000L, 500L, 950L, 10_450L, line));

        for (int i = 0; i < 6; i++) {
            postSigned(payload);
        }

        assertThat(webhookEvent("evt_ac13")).as("受信記録が残る").isPresent();
        assertThat(webhookEvent("evt_ac13").orElseThrow().getAttemptCount())
                .as("試行回数が加算される").isGreaterThanOrEqualTo(5);
        assertThat(webhookEvent("evt_ac13").orElseThrow().getFailedAt())
                .as("failed_at が記録される").isNotNull();
        assertThat(webhookEvent("evt_ac13").orElseThrow().getProcessStatus())
                .as("5 回超過で FAILED 確定").isEqualTo(WebhookProcessStatus.FAILED);

        // 新テーブルを作らないこと（リトライ台帳を別表に切り出さない）。
        List<String> newTables = jdbcTemplate.query("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND (table_name LIKE '%webhook_retr%' OR table_name LIKE '%webhook_dead%'
                        OR table_name LIKE '%webhook_attempt%' OR table_name LIKE '%webhook_failure%')
                """, (rs, i) -> rs.getString(1));
        assertThat(newTables).as("リトライ用の新テーブルを作らない").isEmpty();
    }

    @Test
    @DisplayName("AC14: billing_invoice_lines は UNIQUE(invoice_id, psp_line_ref) で冪等・再送で重複しない")
    void AC14_lineはinvoiceIdとpspLineRefで冪等() throws Exception {
        String twoLines = StripeWebhookPayloadFixture.lineObject(
                "il_ac14_a", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000)
                + ","
                + StripeWebhookPayloadFixture.lineObject(
                "il_ac14_b", "追加席", 2L, 2_000L, 0L, 200L, false, 1000);
        String invoice = StripeWebhookPayloadFixture.invoiceObject("in_ac14", BILLING_CUSTOMER_REF,
                BILLING_SUBSCRIPTION_REF, "open", "jpy", 12_000L, 500L, 1_150L, 12_650L, twoLines);

        postSigned(StripeWebhookPayloadFixture.event("evt_ac14_finalized", "invoice.finalized", invoice));
        // 別 event id・同じ invoice（Stripe の順不同再送）。line は増えてはならない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14_resend", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy", 12_000L, 500L, 1_150L, 12_650L, twoLines)));

        assertThat(linesOf("in_ac14")).as("line は 2 行のまま").hasSize(2);
        assertThat(linesOf("in_ac14")).extracting(BillingInvoiceLineEntity::getPspLineRef)
                .containsExactlyInAnyOrder("il_ac14_a", "il_ac14_b");
    }
}
