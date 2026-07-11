package com.mannschaft.app.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: 課金ドメインの決済ゲートウェイ（ポート）。
 *
 * <p>「自社受取×月額サブスク」を Stripe Checkout（{@code Mode.SUBSCRIPTION}）で行う抽象。<b>Connect
 * （{@code transfer_data}/{@code on_behalf_of}/{@code application_fee}）は一切用いない</b>（D-2・F08.9 会費の
 * destination charge とは別系統）。実装は {@link com.mannschaft.app.billing.StripeBillingPaymentGateway}
 * が既存の {@code StripePaymentProvider}（payment.stripe）へ委譲する。テストではモック差し替え可能にするため
 * billing ドメイン内にポートを置き、Stripe SDK 依存を実装クラスへ封じ込める。</p>
 *
 * <p>webhook のイベント解析（{@code checkout.session.completed} 等）は {@code StripePaymentProvider} を
 * 直接用いる {@link com.mannschaft.app.billing.BillingSubscriptionWebhookService} が担う（F08.9 の
 * {@code MembershipSubscriptionWebhookService} と同じ流儀）。本ポートは「送信系（Checkout 生成・期末解約）」に限定する。</p>
 */
public interface BillingPaymentGateway {

    /**
     * 月額サブスクの Stripe Checkout Session を生成する（{@code Mode.SUBSCRIPTION}・Connect 不使用）。
     *
     * <p>Customer は get-or-create（{@code stripe_customers} 前例）。Price はインライン {@code price_data}
     * （マスタから渡した円額・月次 recurring）で遅延生成する。{@code metadata.billingContractId} に契約 ID を
     * 焼き付け、webhook で PENDING→ACTIVE を突合する。</p>
     *
     * @param operatorUserId 決済者（Stripe Customer の get-or-create キー・USER/TEAM/ORG いずれのスコープでも操作者本人）
     * @param priceJpy       月額（円・マスタ解決値）
     * @param displayName    Stripe Product 表示名（プラン/機能の表示名）
     * @param contractId     billing_contracts.id（{@code metadata.billingContractId}）
     * @param successUrl     決済成功時の遷移先
     * @param cancelUrl      決済中断時の遷移先
     * @return Checkout Session 情報（sessionId / url）
     */
    CheckoutSessionInfo createSubscriptionCheckout(
            Long operatorUserId, int priceJpy, String displayName, UUID contractId,
            String successUrl, String cancelUrl);

    /**
     * 継続課金の Stripe Subscription を期末解約予約する（{@code cancel_at_period_end=true}・D-3）。
     *
     * <p>期末まで利用可・日割り返金なし。現サイクル終了（{@code current_period_end}）を返し、解約応答の
     * 「○月○日まで利用可」と、entitlements の valid_until 保険（webhook 未達でも期末に自動失効）に用いる。</p>
     *
     * @param subscriptionRef Stripe Subscription ID（{@code sub_xxx}）
     * @return 現サイクル終了時刻（{@code current_period_end}）
     */
    Instant cancelAtPeriodEnd(String subscriptionRef);

    /**
     * 継続課金の Stripe Subscription を<b>即時解約</b>する（退会 purge 連動・AC-45）。
     *
     * <p>期末解約（{@link #cancelAtPeriodEnd}）と異なり、退会確定（purge）ユーザーへの課金継続を
     * その場で止める。失敗は例外で上申し、呼び出し側（purge リスナー）が ERROR ログ＋手動照合に委ねる。</p>
     *
     * @param subscriptionRef Stripe Subscription ID（{@code sub_xxx}）
     */
    void cancelImmediately(String subscriptionRef);

    /**
     * Checkout Session 情報（sessionId / url）。
     */
    record CheckoutSessionInfo(String sessionId, String url) {}
}
