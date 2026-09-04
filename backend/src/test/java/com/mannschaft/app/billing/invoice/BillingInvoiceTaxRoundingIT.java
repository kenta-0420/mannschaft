package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 B2: 通貨・税・丸め（AC-34〜AC-39）。
 *
 * <p>Stripe invoice/line を正本として金額を取り、JPY は小数なし・Stripe の line amount を再丸めしない。
 * 各 line の税込/税抜/端数合計が invoice と一致しないと投影を確定しない（設計書 05 §8）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: 通貨・税・丸め IT（AC-34〜39）")
class BillingInvoiceTaxRoundingIT extends AbstractBillingInvoiceWebhookIT {

    @Test
    @DisplayName("AC34: 非 JPY の invoice は fail-closed で投影を拒否する")
    void AC34_非JPYはfailClosedで拒否する() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac34", "BASIC plan", 1L, 1_000L, 0L, 100L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac34_usd", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac34", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "usd", 1_000L, 0L, 100L, 1_100L, line)));

        assertThat(invoiceOf("in_ac34")).as("JPY 以外は投影しない").isEmpty();
        assertThat(invoiceLineRepository.count()).isZero();
    }

    @Test
    @DisplayName("AC35: 税抜の固定検体（単価1,000×数量10・税率10%・割引500）→ 税抜9,500 / 税額950 / 税込10,450")
    void AC35_税抜固定検体の金額が一致する() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac35", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac35", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac35", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 500L, 950L, 10_450L, line)));

        BillingInvoiceEntity invoice = requireInvoice("in_ac35");
        assertThat(invoice.getSubtotalAmount()).isEqualTo(10_000L);
        assertThat(invoice.getDiscountAmount()).isEqualTo(500L);
        assertThat(invoice.getTaxAmount()).as("税額 950").isEqualTo(950L);
        assertThat(invoice.getTotalAmount()).as("税込 10,450").isEqualTo(10_450L);

        BillingInvoiceLineEntity stored = linesOf("in_ac35").get(0);
        assertThat(stored.getAmountExcludingTax() - stored.getDiscountAmount())
                .as("税抜 9,500").isEqualTo(9_500L);
        assertThat(stored.getTaxAmount()).isEqualTo(950L);
        assertThat(stored.getAmountIncludingTax()).as("税込 10,450").isEqualTo(10_450L);
        assertThat(stored.getIncludedInPrice()).as("税抜入力").isFalse();
        assertThat(stored.getTaxRateBasisPoints()).as("税率 10% = 1000bp").isEqualTo(1000);
        assertThat(stored.getTaxNameSnapshot()).as("税名を snapshot する").isEqualTo("消費税");
    }

    @Test
    @DisplayName("AC36: 税込の固定検体（税込単価1,100×数量3・税率10%）→ 税抜3,000 / 税額300 / 税込3,300")
    void AC36_税込固定検体の金額が一致する() throws Exception {
        // Stripe の inclusive line: amount は税込 3,300、tax_amounts.amount は内税 300。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac36", "BASIC プラン（税込）", 3L, 3_300L, 0L, 300L, true, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac36", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac36", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        3_000L, 0L, 300L, 3_300L, line)));

        BillingInvoiceEntity invoice = requireInvoice("in_ac36");
        assertThat(invoice.getTaxAmount()).as("税額 300").isEqualTo(300L);
        assertThat(invoice.getTotalAmount()).as("税込 3,300").isEqualTo(3_300L);

        BillingInvoiceLineEntity stored = linesOf("in_ac36").get(0);
        assertThat(stored.getIncludedInPrice()).as("税込入力").isTrue();
        assertThat(stored.getAmountExcludingTax()).as("税抜 3,000").isEqualTo(3_000L);
        assertThat(stored.getTaxAmount()).as("税額 300").isEqualTo(300L);
        assertThat(stored.getAmountIncludingTax()).as("税込 3,300").isEqualTo(3_300L);
    }

    @Test
    @DisplayName("AC37: 割引を複数 line へ配賦して1円の端数が出るとき line 合計と invoice total が一致する")
    void AC37_端数1円でもline合計とinvoiceTotalが一致する() throws Exception {
        // 割引 501 円を 2 line へ 251 / 250 と配賦する（1 円の端数が片方に寄る）。
        String lines = StripeWebhookPayloadFixture.lineObject(
                "il_ac37_a", "BASIC プラン", 1L, 5_000L, 251L, 0L, false, 0)
                + ","
                + StripeWebhookPayloadFixture.lineObject(
                "il_ac37_b", "追加席", 1L, 5_000L, 250L, 0L, false, 0);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac37", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac37", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 501L, 0L, 9_499L, lines)));

        BillingInvoiceEntity invoice = requireInvoice("in_ac37");
        List<BillingInvoiceLineEntity> stored = linesOf("in_ac37");
        assertThat(stored).hasSize(2);

        long lineSum = stored.stream().mapToLong(BillingInvoiceLineEntity::getAmountIncludingTax).sum();
        assertThat(lineSum)
                .as("line の税込合計（4,749 + 4,750）が invoice total 9,499 と一致する")
                .isEqualTo(invoice.getTotalAmount())
                .isEqualTo(9_499L);
        assertThat(stored).extracting(BillingInvoiceLineEntity::getDiscountAmount)
                .as("配賦した割引をそのまま保存する").containsExactlyInAnyOrder(251L, 250L);
    }

    @Test
    @DisplayName("AC38: quantity は DECIMAL(12,3) の精度で保存され金額は Stripe の値と一致する")
    void AC38_quantityが小数精度で保存され金額がStripeと一致する() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac38", "従量課金", 3L, 4_500L, 0L, 450L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac38", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac38", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        4_500L, 0L, 450L, 4_950L, line)));

        BillingInvoiceLineEntity stored = linesOf("in_ac38").get(0);
        assertThat(stored.getQuantity())
                .as("DECIMAL(12,3) の小数精度で保持される")
                .isEqualByComparingTo(new BigDecimal("3.000"));
        assertThat(stored.getQuantity().scale()).as("scale=3 が保たれる").isEqualTo(3);
        assertThat(stored.getAmountExcludingTax()).as("Stripe の amount と一致").isEqualTo(4_500L);
        assertThat(stored.getAmountIncludingTax()).isEqualTo(4_950L);
    }

    @Test
    @DisplayName("AC39: 金額が負・税率が過大・税情報が null なら投影を確定しない")
    void AC39_金額負や税率過大や税null時は投影しない() throws Exception {
        // (1) 金額が負。
        String negative = StripeWebhookPayloadFixture.lineObject(
                "il_ac39a", "不正 line", 1L, -1_000L, 0L, 0L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac39_negative", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac39a", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy", -1_000L, 0L, 0L, -1_000L, negative)));
        assertThat(invoiceOf("in_ac39a")).as("負の金額は投影しない").isEmpty();

        // (2) 税率が過大（10000bp = 100% 超）。
        String overTaxed = StripeWebhookPayloadFixture.lineObject(
                "il_ac39b", "税率過大", 1L, 1_000L, 0L, 5_000L, false, 50_000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac39_overtax", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac39b", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy", 1_000L, 0L, 5_000L, 6_000L, overTaxed)));
        assertThat(invoiceOf("in_ac39b")).as("税率 100% 超は投影しない").isEmpty();

        // (3) 税情報が無い（tax_rates 空・税額の裏付けが取れない）。
        String noTax = StripeWebhookPayloadFixture.lineObject(
                "il_ac39c", "税情報なし", 1L, 1_000L, 0L, 100L, false, null);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac39_notax", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac39c", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy", 1_000L, 0L, 100L, 1_100L, noTax)));
        assertThat(invoiceOf("in_ac39c"))
                .as("税額があるのに税名/税率が無い invoice は投影しない").isEmpty();
    }
}
