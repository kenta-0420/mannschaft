package com.mannschaft.app.payment.connect;

/**
 * F22.1 謝礼決済: 受領/支払主体の種別。
 *
 * <p>{@code connect_accounts.scope_kind} および
 * {@code escrow_transactions.payee_kind}/{@code payer_scope_kind} に対応する。
 * DB 側は VARCHAR(8) + CHECK 制約で表現する。</p>
 */
public enum ScopeKind {
    USER,
    TEAM,
    ORG
}
