package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.EntitlementScopeKind;

import java.util.UUID;

/**
 * F20.1 PR5: invoice 投影の所有者（scope-owned Customer と、紐づく契約）。
 *
 * <p>所有判定は {@code psp_subscription_ref} の DB ヒット<b>単独では行わない</b>。
 * {@code invoice.customer} が scope 所有の {@code billing_customers} に一致することを併せて確かめる
 * （AC-25。subscription ref だけで断定すると、他人の customer の invoice を自 scope の投影として
 * 取り込んでしまう）。</p>
 *
 * @param billingCustomerId scope 所有の {@code billing_customers.id}
 * @param contractId        紐づく {@code billing_contracts.id}（subscription 未紐付なら {@code null}）
 * @param scopeKind         scope 種別
 * @param scopeId           scope ID
 * @param organizationId    組織 ID（scope が ORG 以外なら {@code null} でよい）
 */
public record BillingInvoiceOwner(
        UUID billingCustomerId,
        UUID contractId,
        EntitlementScopeKind scopeKind,
        Long scopeId,
        Long organizationId) {
}
