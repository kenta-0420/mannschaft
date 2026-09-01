package com.mannschaft.app.billing.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link BillingCheckoutReconciliationQueue} の実装（BC-23 の補償退避）。
 *
 * <p>「Stripe 側に Checkout Session が実在するのに DB 側が倒れた」事実を、必ず失われない形で残す。</p>
 *
 * <h2>なぜ DB 表ではなくログなのか（実測に基づく判断・殿の裁可を仰ぐ点）</h2>
 * <p>V196 に照合の受け皿となる表は存在しない。実測した候補は次の 2 つで、いずれも本 port の
 * 引数（{@code stripeSessionId} / {@code stripeCustomerRef} / {@code idempotencyId}）だけでは
 * <b>行を作れない</b>:</p>
 * <ul>
 *   <li>{@code billing_contract_operations}（{@code status='RECONCILIATION_REQUIRED'} を持つ）—
 *       {@code contract_id} / {@code billing_customer_id} が {@code NOT NULL} かつ
 *       {@code billing_contracts} / {@code billing_customers} への FK であり、本 port は
 *       どちらの UUID も受け取らない。さらに {@code chk_bco_kind} は
 *       {@code PLAN_CHANGE / CANCEL / RESUME / DOWNGRADE_TO_CANCEL / MIGRATION / MEMBER_REPRICE / REFUND}
 *       のみを許し、Checkout 起票の照合に当たる kind が無い。Stripe customer ref から PENDING 契約を
 *       逆引きする案は、同一 Customer が複数の PENDING 契約（PLAN と ADDON 等）を持ちうるため
 *       別契約を照合対象と取り違えうる。金銭に関わる推測は行わない。</li>
 *   <li>{@code stripe_webhook_events}（V196 で {@code failed_at} / {@code attempt_count} /
 *       {@code stripe_object_ref} を獲得）— 表とその Entity（{@code StripeWebhookEventEntity}）は
 *       <b>payment ドメイン</b>に属する。billing から Entity を直接参照することは
 *       {@code CrossDomainEntityImportArchTest} が禁じている（{@code StripeBillingPaymentGateway}
 *       javadoc に CI 差し戻しの実測記録あり）。加えて Stripe Customer ref を格納できる列が無い。</li>
 * </ul>
 * <p>したがって「DB 表に載せる」には新規 migration か port 署名の拡張
 * （{@code billingContractId} / {@code billingCustomerId} を渡す）が要る。本工程は
 * 新規 migration 追加と試練テストの署名変更のいずれも禁じられているため、<b>握りつぶさず</b>
 * ERROR ログとして確実に残す方式を採り、恒久的な受け皿は殿の裁可を仰ぐ。</p>
 *
 * <h2>ログの約束</h2>
 * <p>{@value #MARKER} を先頭に置いた単一行で出す（運用アラートはこの marker を鍵にする）。
 * Checkout URL・return state token・PII は出さない。出すのは Stripe の不透明 ID
 * （{@code cs_...} / {@code cus_...}）と退避の識別子までに留める。</p>
 */
@Slf4j
@Component
class BillingCheckoutReconciliationQueueAdapter implements BillingCheckoutReconciliationQueue {

    /** 運用アラートが購読する marker。変更する場合は監視設定と同時に行うこと。 */
    static final String MARKER = "BILLING_CHECKOUT_RECONCILIATION_REQUIRED";

    @Override
    public void enqueue(String stripeSessionId, String stripeCustomerRef, UUID idempotencyId) {
        log.error("{} stripeSessionId={} stripeCustomerRef={} idempotencyId={}",
                MARKER, stripeSessionId, stripeCustomerRef, idempotencyId);
    }
}
