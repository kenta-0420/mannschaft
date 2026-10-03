package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.1 PR5 試練 B3: F08.12（請求書 PDF）への受け渡し契約（AC-40〜AC-43）。
 *
 * <p>請求書の発行者はプラットフォーム（運営）であり、請求先は投影時点の snapshot を不変保存する。
 * 投影データは 7 年保持され、削除・改変する経路を作らない（設計書 05 §8）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: F08.12 受け渡し契約 IT（AC-40〜43）")
class BillingInvoiceDocumentContractIT extends AbstractBillingInvoiceWebhookIT {

    private static final String INVOICE_REF = "in_doc_contract";

    private void projectPaidInvoice() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_doc", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_doc_paid", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject(INVOICE_REF, BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy",
                        10_000L, 500L, 950L, 10_450L, line)));
    }

    @Test
    @DisplayName("AC40: issuer_name_snapshot にプラットフォーム名（運営）が入る")
    void AC40_issuerNameSnapshotにプラットフォーム名が入る() throws Exception {
        projectPaidInvoice();

        BillingInvoiceEntity invoice = requireInvoice(INVOICE_REF);
        assertThat(invoice.getIssuerNameSnapshot())
                .as("発行者名が空でない").isNotBlank();
        assertThat(invoice.getIssuerNameSnapshot())
                .as("発行者は請求先（利用者）ではなくプラットフォーム（運営）である")
                .isNotEqualTo("請求先 太郎");
        assertThat(invoice.getIssuerNameSnapshot())
                .as("運営の名で発行される").containsIgnoringCase("Mannschaft");
    }

    @Test
    @DisplayName("AC41: billing_name / billing_email / billing_address_snapshot を投影時に保存する")
    void AC41_請求先snapshotを投影時に保存する() throws Exception {
        projectPaidInvoice();

        BillingInvoiceEntity invoice = requireInvoice(INVOICE_REF);
        assertThat(invoice.getBillingNameSnapshot()).isEqualTo("請求先 太郎");
        assertThat(invoice.getBillingEmailSnapshot()).isEqualTo("billing-taro@example.com");
        assertThat(invoice.getBillingAddressSnapshot())
                .as("請求先住所を JSON snapshot として保存する")
                .isNotNull()
                .contains("千代田");
    }

    @Test
    @DisplayName("AC42: invoice の UUID から一意取得でき issuer・請求先 snapshot・金額・税・明細行がすべて読める")
    void AC42_invoiceのUUIDから請求書に必要な全項目が読める() throws Exception {
        projectPaidInvoice();

        BillingInvoiceEntity projected = requireInvoice(INVOICE_REF);
        BillingInvoiceEntity byId = invoiceRepository.findById(projected.getId())
                .orElseThrow(() -> new AssertionError("UUID から一意取得できない"));

        assertThat(byId.getIssuerNameSnapshot()).as("発行者").isNotBlank();
        assertThat(byId.getBillingNameSnapshot()).as("請求先宛名").isNotBlank();
        assertThat(byId.getBillingEmailSnapshot()).as("請求先メール").isNotBlank();
        assertThat(byId.getBillingAddressSnapshot()).as("請求先住所").isNotNull();
        assertThat(byId.getCurrency()).isEqualTo("JPY");
        assertThat(byId.getSubtotalAmount()).isEqualTo(10_000L);
        assertThat(byId.getDiscountAmount()).isEqualTo(500L);
        assertThat(byId.getTaxAmount()).isEqualTo(950L);
        assertThat(byId.getTotalAmount()).isEqualTo(10_450L);
        assertThat(byId.getPeriodStart()).as("請求期間 開始").isNotNull();
        assertThat(byId.getPeriodEnd()).as("請求期間 終了").isNotNull();

        List<BillingInvoiceLineEntity> lines = invoiceLineRepository.findByInvoiceId(byId.getId());
        assertThat(lines).as("明細行が読める").hasSize(1);
        assertThat(lines.get(0).getDescriptionSnapshot()).isEqualTo("BASIC プラン");
        assertThat(lines.get(0).getTaxNameSnapshot()).as("税名").isEqualTo("消費税");
        assertThat(lines.get(0).getTaxRateBasisPoints()).as("税率").isEqualTo(1000);
    }

    @Test
    @DisplayName("AC43: 投影データを削除・改変する経路を作らない（物理削除 API を設けない）")
    void AC43_投影データの物理削除経路を作らない() {
        assertNoDeclaredDeleteMethod(BillingInvoiceJpaRepository.class);
        assertNoDeclaredDeleteMethod(BillingInvoiceLineJpaRepository.class);
        assertNoDeclaredDeleteMethod(BillingInvoiceAdjustmentJpaRepository.class);

        // 投影 3 表に ON DELETE CASCADE を張らない（親の消去で静かに消えないこと）。
        List<String> cascades = jdbcTemplate.query("""
                SELECT CONCAT(rc.table_name, '.', rc.constraint_name)
                  FROM information_schema.referential_constraints rc
                 WHERE rc.constraint_schema = DATABASE()
                   AND rc.table_name IN ('billing_invoices','billing_invoice_lines','billing_invoice_adjustments')
                   AND rc.delete_rule = 'CASCADE'
                """, (rs, i) -> rs.getString(1));
        assertThat(cascades).as("投影表に CASCADE DELETE を張らない").isEmpty();
    }

    private void assertNoDeclaredDeleteMethod(Class<?> repositoryInterface) {
        List<String> deleteMethods = Arrays.stream(repositoryInterface.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.toLowerCase().contains("delete")
                        || name.toLowerCase().contains("remove")
                        || name.toLowerCase().contains("purge"))
                .toList();
        assertThat(deleteMethods)
                .as("%s は投影データの物理削除メソッドを宣言しない", repositoryInterface.getSimpleName())
                .isEmpty();
    }
}
