package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.payment.WebhookProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 A3: トランザクション境界（AC-10 / AC-20 / AC-21 / AC-26）。
 *
 * <p><b>なぜモック例外では測れないのか</b>: 投影の呼び出し先に try/catch があると、モックの例外は
 * そこで握られて rollback-only にならない。{@code @Transactional} の自己呼び出し失効（同一 Bean 内の
 * private/self 呼び出しでプロキシを通らず、境界が張られていない状態）は、
 * <b>実 DB の永続化を実際に失敗させないと再現しない</b>。そこで
 * {@code billing_invoice_lines} に検体専用の CHECK 制約を張り、line の INSERT を DB 層で殺す。</p>
 *
 * <p><b>本クラスに {@code @Transactional} を付けない</b>: 付けるとテストの tx にぶら下がって
 * commit が起きず、測りたい境界が発火しないまま<b>偽の緑</b>になる。
 * MySQL の DDL は暗黙コミットを伴うため、制約の付け外しはトランザクション外で行う。</p>
 *
 * <p>判別列の値は共有コンテナで他テストと衝突しない専用の番兵文字列にする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: invoice 投影のトランザクション境界 IT（AC-10/20/21/26）")
class BillingInvoiceProjectionTransactionBoundaryIT extends AbstractBillingInvoiceWebhookIT {

    /** この IT だけが使う番兵。共有コンテナに残留しても他テストの検体と衝突しない。 */
    private static final String TX_PROBE_DESCRIPTION = "AC26_TX_BOUNDARY_PROBE_F20_1_PR5";

    private static final String CONSTRAINT_NAME = "chk_bil_ac26_tx_boundary_probe";

    @BeforeEach
    void addProbeConstraint() {
        dropProbeConstraintQuietly();
        jdbcTemplate.execute(
                "ALTER TABLE billing_invoice_lines ADD CONSTRAINT " + CONSTRAINT_NAME
                        + " CHECK (description_snapshot <> '" + TX_PROBE_DESCRIPTION + "')");
    }

    @AfterEach
    void dropProbeConstraint() {
        dropProbeConstraintQuietly();
    }

    private void dropProbeConstraintQuietly() {
        try {
            jdbcTemplate.execute("ALTER TABLE billing_invoice_lines DROP CHECK " + CONSTRAINT_NAME);
        } catch (RuntimeException ignored) {
            // 未付与のときは何もしない（症状を隠すためではなく、冪等な後片付けのため）。
        }
    }

    /** line の INSERT が DB 層で必ず失敗する検体（invoice ヘッダは正常）。 */
    private String probeInvoice(String invoiceRef) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, TX_PROBE_DESCRIPTION, 10L, 10_000L, 500L, 950L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, "paid",
                "jpy", 10_000L, 500L, 950L, 10_450L, line);
    }

    @Test
    @DisplayName("AC26: line の永続化が実 DB で失敗したとき invoice ヘッダも残らない（@Transactional 自己呼び出し失効の検出）")
    void AC26_line永続化失敗時にinvoiceヘッダも残らない() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac26_probe", "invoice.paid", probeInvoice("in_ac26")));

        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac26"))
                .as("line が落ちたのに invoice ヘッダだけ commit されているなら境界が張れていない")
                .isEmpty();
        assertThat(invoiceLineRepository.count()).as("line も残らない").isZero();
    }

    @Test
    @DisplayName("AC20: 投影が失敗したとき契約期間の延長も一体に巻き戻る")
    void AC20_投影失敗時に契約期間延長も巻き戻る() throws Exception {
        var before = billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId)
                .orElseThrow().getCurrentPeriodEnd();

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac20_probe", "invoice.paid", probeInvoice("in_ac20tx")));

        assertThat(billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId)
                .orElseThrow().getCurrentPeriodEnd())
                .as("invoice 投影と契約期間延長は一体に成否する（片方だけ commit されない）")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("AC10: billing 所有の一時失敗は StripeWebhookRetryableException に包まれ 5xx になる")
    void AC10_billing所有の一時失敗は5xxになる() throws Exception {
        int status = postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac10_probe", "invoice.paid", probeInvoice("in_ac10"))).getResponse().getStatus();

        assertThat(status)
                .as("billing 所有の一時 DB 失敗は 200 で握らず 5xx を返して Stripe 再送に委ねる")
                .isGreaterThanOrEqualTo(500);
    }

    @Test
    @DisplayName("AC21: 失敗時は event_id が確定されず Stripe 再送で再試行できる")
    void AC21_失敗時はeventIdが確定されず再試行できる() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac21_probe", "invoice.paid", probeInvoice("in_ac21")));

        assertThat(webhookEvent("evt_ac21_probe"))
                .as("失敗しても受信記録自体は残る（再送で拾えるように）").isPresent();
        assertThat(webhookEvent("evt_ac21_probe").orElseThrow().getProcessStatus())
                .as("PROCESSED/IGNORED で確定させない")
                .isIn(WebhookProcessStatus.RECEIVED, WebhookProcessStatus.FAILED);

        // 制約を外して再送すると、今度は成功して投影が確定できること（＝再試行が塞がれていない）。
        dropProbeConstraintQuietly();
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac21_probe", "invoice.paid", probeInvoice("in_ac21")));

        assertThat(invoiceRepository.findByPspInvoiceRef("in_ac21"))
                .as("Stripe 再送で投影がリカバリできる").isPresent();
        assertThat(webhookEvent("evt_ac21_probe").orElseThrow().getProcessStatus())
                .isEqualTo(WebhookProcessStatus.PROCESSED);
    }
}
