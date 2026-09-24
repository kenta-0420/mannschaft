package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6b-1 — I群 回帰（AC-140・AC-141）の受け入れテスト（試練D・第5隊）。
 *
 * <p>「一式壊れていない」では追跡できないため、PR6a（解約・撤回）と PR5
 * （invoice投影・Portal）の既存試練クラスを<b>対象テスト名として列挙して固定</b>する。
 * このテストは各クラスが存在すること（削除・改名されていないこと）を reflection で確認する
 * だけの<b>軽量な存在ガード</b>であり、実際の green/red 判定は各クラス自身の CI 実行が担う。
 * Docker 不要（クラスロードのみ）で常に実行できるようにしてある。</p>
 */
@DisplayName("PR6b-1 回帰対象の固定リスト（I群 AC-140・141）")
class BillingPr6aPr5RegressionFixedListTest {

    // ═════════ AC-140: PR6aの解約・撤回が壊れていない ═════════

    private static final List<String> PR6A_CANCEL_RESUME_REGRESSION_CLASSES = List.of(
            "com.mannschaft.app.billing.api.BillingCancelResumeAuditRegressionRedIT",
            "com.mannschaft.app.billing.api.BillingCancelResumeAuthzRedIT",
            "com.mannschaft.app.billing.api.BillingCancelResumeDisplayContractRedIT",
            "com.mannschaft.app.billing.api.BillingCancelResumeHandoverExclusionRedIT",
            "com.mannschaft.app.billing.api.BillingCancelStripeAppliedPeriodEndIT",
            "com.mannschaft.app.billing.BillingCancelResumePortContractTest",
            "com.mannschaft.app.billing.BillingCancelStateTest",
            "com.mannschaft.app.billing.BillingResumeVersusHandoverNightlyReconcileTest",
            "com.mannschaft.app.common.architecture.BillingCancelResumeGuardExistenceRedIT");

    @Test
    @DisplayName("AC-140: PR6aの解約・撤回試練クラスが削除・改名されていない（固定リスト）")
    void AC140_PR6aの解約撤回試練クラスが存在する() {
        assertClassesExist(PR6A_CANCEL_RESUME_REGRESSION_CLASSES, "PR6a 解約・撤回");
    }

    // ═════════ AC-141: PR5のinvoice投影・Portalが壊れていない ═════════

    private static final List<String> PR5_INVOICE_PORTAL_REGRESSION_CLASSES = List.of(
            "com.mannschaft.app.billing.api.BillingCustomerPortalApplicationServiceTest",
            "com.mannschaft.app.billing.api.BillingCustomerPortalControllerAuthzOrderTest",
            "com.mannschaft.app.billing.api.BillingCustomerPortalRateLimiterAdapterTest",
            "com.mannschaft.app.billing.api.BillingCustomerPortalStripeGatewayTest",
            "com.mannschaft.app.billing.api.BillingInvoiceApiAuthorizationRedIT",
            "com.mannschaft.app.billing.api.BillingInvoiceApiContractRedIT",
            "com.mannschaft.app.billing.api.BillingInvoiceApiQueryEfficiencyRedIT",
            "com.mannschaft.app.billing.api.BillingInvoiceCursorTest",
            "com.mannschaft.app.billing.api.BillingPortalSessionApiIT",
            "com.mannschaft.app.billing.api.BillingPortalSessionContractTrialTest",
            "com.mannschaft.app.billing.invoice.BillingInvoiceAdjustmentProjectionIT",
            "com.mannschaft.app.billing.invoice.BillingInvoiceAdjustmentWebhookServiceTest",
            "com.mannschaft.app.billing.invoice.BillingInvoiceDocumentContractIT",
            "com.mannschaft.app.billing.invoice.BillingInvoiceProjectionServiceTest",
            "com.mannschaft.app.billing.invoice.BillingInvoiceProjectionTransactionBoundaryIT",
            "com.mannschaft.app.billing.invoice.BillingInvoiceProjectionWebhookIT",
            "com.mannschaft.app.billing.invoice.BillingInvoiceTaxRoundingIT");

    @Test
    @DisplayName("AC-141: PR5のinvoice投影・Portal試練クラスが削除・改名されていない（固定リスト）")
    void AC141_PR5のinvoice投影とPortal試練クラスが存在する() {
        assertClassesExist(PR5_INVOICE_PORTAL_REGRESSION_CLASSES, "PR5 invoice投影・Portal");
    }

    private void assertClassesExist(List<String> fqcns, String label) {
        for (String fqcn : fqcns) {
            try {
                Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(label + " の回帰対象クラス " + fqcn
                        + " が見つからない（削除・改名されていないか確認すること。"
                        + "AC-140/141は『対象テスト名を列挙して固定する』要求であり、"
                        + "このリスト自体が正本）", e);
            }
        }
        assertThat(fqcns).as(label + " の固定リストが空でないこと").isNotEmpty();
    }
}
