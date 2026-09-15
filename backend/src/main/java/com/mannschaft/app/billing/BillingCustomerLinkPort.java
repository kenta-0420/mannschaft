package com.mannschaft.app.billing;

import java.util.UUID;

/**
 * Billing Center PR6a: 契約が属する {@code billing_customers} 行を解決するポート。
 *
 * <h2>なぜこのポートが必要か（実在欠陥の是正）</h2>
 * <p>{@code billing_contract_operations.billing_customer_id} は V196 で
 * <b>NOT NULL ＋ {@code billing_customers} への FK</b> として定義された。ところが F20.1 の決済フロー
 * （{@code BillingContractService#startPaidContract} → {@code activatePaidContract}）で作られる契約は
 * {@code psp_customer_ref} は焼き付けるが {@code billing_customer_id} を<b>一度も設定しない</b>。
 * つまり本番には「有償・ACTIVE・PSP 紐付あり、しかし billing_customer_id が NULL」の契約が実在する。</p>
 *
 * <p>{@code billing_customers} の Entity / Repository は {@code billing.api} パッケージにあり、
 * {@code billing} から {@code billing.api} を参照するのは依存の向きが逆である
 * （現行コードで {@code billing} → {@code billing.api} の import は1件も無い）。そこで解決の
 * <b>契約だけ</b>を {@code billing} 側のポートとして置き、実装を {@code billing.api} 側に置く。</p>
 *
 * <p>Entity を露出しない（D-1 API 境界の番人）。返すのは ID だけである。</p>
 */
public interface BillingCustomerLinkPort {

    /**
     * scope が所有する {@code billing_customers} を解決し、無ければ引き上げ（provision）て ID を返す。
     *
     * <p>{@code uk_bcu_scope (scope_kind, scope_id)} により scope ごとに高々1行であり、本メソッドは
     * 冪等である。呼び出し元のトランザクションに参加する（{@code MANDATORY}）。</p>
     *
     * @param scopeKind      USER / TEAM / ORG
     * @param scopeId        scope の ID
     * @param organizationId テナント（USER スコープは {@code null} 可）
     * @param pspCustomerRef 契約に焼き付いている Stripe Customer ID（{@code cus_xxx}・{@code null} 可）
     * @return {@code billing_customers.id}
     */
    UUID resolveOrProvision(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId, String pspCustomerRef);
}
