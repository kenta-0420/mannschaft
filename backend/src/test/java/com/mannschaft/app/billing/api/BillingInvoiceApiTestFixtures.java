package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * F20.1 課金履歴 API（AC-44〜AC-60）試練用の検体組み立てヘルパ。
 *
 * <p>実装は未着手であり、本クラスは <b>検体（fixture）だけ</b>を提供する。
 * V196 DDL の NOT NULL / CHECK を満たす最小の行を作る。</p>
 */
final class BillingInvoiceApiTestFixtures {

    private BillingInvoiceApiTestFixtures() {
    }

    /** 指定 scope の invoice を1件組み立てる（period_end は null 可）。 */
    static BillingInvoiceEntity invoice(
            EntitlementScopeKind scopeKind,
            long scopeId,
            UUID billingCustomerId,
            String pspInvoiceRef,
            Instant periodEnd,
            long total) {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        return BillingInvoiceEntity.builder()
                .billingCustomerId(billingCustomerId)
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .pspInvoiceRef(pspInvoiceRef)
                .billingReason("subscription_cycle")
                .status("PAID")
                .periodStart(periodEnd == null ? null : periodEnd.minusSeconds(86400L * 30))
                .periodEnd(periodEnd)
                .currency("JPY")
                .subtotalAmount(total)
                .discountAmount(0L)
                .taxAmount(0L)
                .totalAmount(total)
                .issuerNameSnapshot("Mannschaft")
                .billingNameSnapshot("請求先 太郎")
                .billingEmailSnapshot("billing@example.com")
                .billingAddressSnapshot(
                        "{\"country\":\"JP\",\"line1\":\"千代田区1-1-1\","
                                + "\"city\":\"東京都\",\"postalCode\":\"1000001\"}")
                .paidAt(now)
                .finalizedAt(now)
                .version(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** invoice 明細行を1件組み立てる。 */
    static BillingInvoiceLineEntity line(UUID invoiceId, String pspLineRef, long amountExcludingTax) {
        long tax = Math.round(amountExcludingTax * 0.1);
        return BillingInvoiceLineEntity.builder()
                .invoiceId(invoiceId)
                .pspLineRef(pspLineRef)
                .descriptionSnapshot("プラン利用料 " + pspLineRef)
                .quantity(BigDecimal.valueOf(1))
                .amountExcludingTax(amountExcludingTax)
                .discountAmount(0L)
                .taxNameSnapshot("消費税")
                .taxRateBasisPoints(1000)
                .taxAmount(tax)
                .includedInPrice(Boolean.FALSE)
                .amountIncludingTax(amountExcludingTax + tax)
                .createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
    }

    /** 調整（返金等）を1件組み立てる。 */
    static BillingInvoiceAdjustmentEntity adjustment(UUID invoiceId, String pspObjectRef, long amount) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return BillingInvoiceAdjustmentEntity.builder()
                .invoiceId(invoiceId)
                .kind("REFUND")
                .pspObjectRef(pspObjectRef)
                .amount(amount)
                .currency("JPY")
                .status("SUCCEEDED")
                .reason("requested_by_customer")
                .effectiveAt(now)
                .version(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
