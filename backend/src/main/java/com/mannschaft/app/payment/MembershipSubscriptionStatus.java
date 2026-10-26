package com.mannschaft.app.payment;

/**
 * F08.9 継続課金（membership_subscriptions）のライフサイクル状態。
 *
 * <p>状態遷移（02_api_design.md §4.2）:</p>
 * <pre>
 * PENDING ──(初回 invoice.paid)──▶ ACTIVE
 * ACTIVE ──(invoice.payment_failed)──▶ PAST_DUE ──(再試行 invoice.paid)──▶ ACTIVE
 * PAST_DUE ──(Stripe 再試行尽き subscription.deleted)──▶ CANCELLED
 * ACTIVE/PAST_DUE ──(期末解約 cancel_at_period_end)──▶ 期末に CANCELLED
 * </pre>
 *
 * <p>{@code cancel_at_period_end} / {@code skip_until}（今月スキップ）は {@code ACTIVE} 内の利用者操作であり、
 * 本 status とは独立に表現する（status に専用値は設けない）。{@code EXPIRED} は将来の明示的失効遷移用に予約。</p>
 *
 * <p>DB 側は VARCHAR(16) + CHECK 制約で表現する（{@code chk_ms_status}）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.1 / 02_api_design.md §4</p>
 */
public enum MembershipSubscriptionStatus {

    /** 起票直後（Stripe Subscription 作成済・初回 invoice.paid 待ち）。 */
    PENDING,

    /** 有効（課金成立・ペイウォール閲覧可）。 */
    ACTIVE,

    /** 支払い失敗中（督促状態・grace 内は閲覧可・Stripe smart retries に委譲）。 */
    PAST_DUE,

    /** 解約済（期末解約の期末到達 or Stripe subscription.deleted）。 */
    CANCELLED,

    /** 失効（将来の明示的失効遷移用に予約）。 */
    EXPIRED
}
