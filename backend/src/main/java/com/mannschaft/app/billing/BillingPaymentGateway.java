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

    // ========================================
    // 柱③-B PR-2 請求支払者の引継（設計書 billing_payer_handover_design.md）
    // ========================================

    /**
     * 引継用の新サブスク Checkout Session を生成する（{@code trial_end}＝旧期末・設計書 §2.3・AC-4/AC-5）。
     *
     * <p>新サブスクは {@code trialing} で作成され、<b>旧契約の期末まで一切請求されない</b>。trial 終了時刻を
     * 旧 {@code current_period_end} と同一 unix 秒に揃えるため、旧期末と新開始の間に<b>隙間も重複も生じない</b>。</p>
     *
     * @param newPayerUserId    新 payer（承諾した ADMIN・Stripe Customer の get-or-create キー）
     * @param priceJpy          月額（円）
     * @param displayName       Stripe Product 表示名
     * @param newContractId     引継先 {@code billing_contracts.id}（{@code PENDING_HANDOVER} で先行作成済み）
     * @param oldContractId     引継元 {@code billing_contracts.id}（監査用 metadata）
     * @param handoverRequestId {@code billing_payer_handover_requests.id}（回復経路の突合キー・冪等キーの単位）
     * @param trialEnd          旧契約の {@code current_period_end}（<b>未来時刻必須</b>・過去なら要求作成時点で拒否済み）
     * @param successUrl        決済成功時の遷移先
     * @param cancelUrl         決済中断時の遷移先
     * @return Checkout Session 情報（sessionId / url）
     */
    CheckoutSessionInfo createHandoverSubscriptionCheckout(
            Long newPayerUserId, int priceJpy, String displayName,
            UUID newContractId, UUID oldContractId, UUID handoverRequestId,
            Instant trialEnd, String successUrl, String cancelUrl);

    /**
     * 承諾確定と同時に旧サブスクを期末解約予約する（{@code cancel_at_period_end=true}・設計書 §2.3 R3-P1-3）。
     *
     * <p>これを承諾確定（{@code checkout.session.completed}）の時点で行うことが、二重課金を<b>構造的に</b>
     * 消す要である。以後どの後続手順（切替TX・trial 終了時の請求等）が失敗しても、旧サブスクは Stripe 側の
     * 保証で必ず期末に終了する（AC-31）。冪等キーは通常解約の {@code billing-cancel-*} とは別名前空間の
     * {@code billing-handover-schedule-cancel-{handoverRequestId}} を用い、同一 subscriptionRef に対して
     * 通常解約と引継予約が同時に走ってもキー衝突（パラメータ不一致エラー）を起こさない（設計書 §3.4・AC-24）。</p>
     *
     * @param subscriptionRef   旧 Stripe Subscription ID（{@code sub_xxx}）
     * @param handoverRequestId 冪等キーの単位
     * @return 旧サブスクの現サイクル終了時刻（{@code current_period_end}）
     */
    Instant scheduleCancelAtPeriodEndForHandover(String subscriptionRef, UUID handoverRequestId);

    /**
     * 引継が旧期末前に {@code FAILED} 確定した場合、旧サブスクの期末解約予約を差し戻す（設計書 §3.6.1・AC-32）。
     *
     * <p>呼び出し側は成功後に {@code old_cancel_scheduled_at} を<b>必ず対で NULL クリア</b>すること
     * （クリアし忘れると、同一契約への再要求時に「予約済み」と誤認され夜次照合の検出対象から外れる）。</p>
     *
     * @param subscriptionRef   旧 Stripe Subscription ID（{@code sub_xxx}）
     * @param handoverRequestId 冪等キーの単位（{@code billing-handover-revert-cancel-*}）
     */
    void revertCancelAtPeriodEndForHandover(String subscriptionRef, UUID handoverRequestId);

    /**
     * 引継の<b>新</b> trial サブスクを即時解約する（設計書 §3.4・§3.6・AC-30/AC-32）。
     *
     * <p>trial 中は無課金のため即時解約して差し支えない。新 payer の離脱・{@code pending_setup_intent}
     * 未解決による {@code FAILED} 確定時に呼ぶ。<b>旧</b>サブスクに対して即時解約を用いてはならない
     * （R3-P1-3 で {@code cancelImmediately} 方式は廃止された）。</p>
     *
     * @param subscriptionRef   新 Stripe Subscription ID（{@code sub_xxx}）
     * @param handoverRequestId 冪等キーの単位（{@code billing-handover-cancel-new-*}）
     */
    void cancelHandoverNewSubscription(String subscriptionRef, UUID handoverRequestId);

    /**
     * Stripe Subscription の実物スナップショットを取得する（設計書 §3.6.1(b)・§3.6 二段検証）。
     *
     * <p>DB の記録ではなく Stripe 側の実値で判定するために用いる。</p>
     *
     * @param subscriptionRef Stripe Subscription ID（{@code sub_xxx}）
     * @return スナップショット
     */
    SubscriptionSnapshot retrieveSubscription(String subscriptionRef);

    /**
     * 新サブスクの二重作成を防ぐ回復経路（設計書 §3.2・AC-7/AC-25/AC-33）。
     *
     * <p>「Stripe には作成済みだが DB へ {@code psp_new_subscription_ref} を書き戻す前に落ちた」ケースを
     * 回収する。新 payer の Customer に紐づく Subscription を <b>List API で全ページ走査</b>し、
     * {@code metadata.handoverRequestId} 一致をクライアント側で絞り込む。List は read-after-write 整合のため
     * 待機間隔は不要（Search API の鮮度遅延という概念が存在しない）。</p>
     *
     * <p>呼び出し側は必ず「DB の ref が空」→「本メソッドが空」の<b>両方</b>を確認してから新規作成すること。</p>
     *
     * @param newPayerUserId    新 payer（Stripe Customer 解決キー）
     * @param handoverRequestId 突合する {@code metadata.handoverRequestId}
     * @return 既存サブスクの ID（無ければ空）
     */
    java.util.Optional<String> findHandoverSubscriptionRef(Long newPayerUserId, UUID handoverRequestId);

    /**
     * 新 payer が有効な支払い手段（既定 PaymentMethod）を持つかを判定する（設計書 §3.6・AC-16/AC-19）。
     *
     * <p>ACCEPTED→SWITCHING の前（二段検証の1段目）に必須。未登録のまま新サブスクを作ると、trial 終了時に
     * {@code past_due} または {@code canceled} へ落ちるため、事前に {@code REQUIRES_PAYMENT_METHOD} へ差し戻す。</p>
     *
     * @param userId 対象ユーザー
     * @return 既定 PaymentMethod が登録済みなら true
     */
    boolean hasUsablePaymentMethod(Long userId);

    /**
     * Checkout Session 情報（sessionId / url）。
     */
    record CheckoutSessionInfo(String sessionId, String url) {}

    /**
     * Stripe Subscription 実物のスナップショット（設計書 §3.6.1）。
     *
     * @param subscriptionRef      Stripe Subscription ID
     * @param status               Stripe ステータス（{@code trialing}/{@code active}/{@code canceled} 等）
     * @param cancelAtPeriodEnd    期末解約が予約済みか
     * @param currentPeriodStart   現サイクル開始（期末境界越え判定に用いる・null 可）
     * @param currentPeriodEnd     現サイクル終了（null 可）
     * @param pendingSetupIntentId 未解決 SetupIntent（SCA/3DS 未完了時のみ非 null）
     */
    record SubscriptionSnapshot(String subscriptionRef, String status, boolean cancelAtPeriodEnd,
                                Instant currentPeriodStart, Instant currentPeriodEnd,
                                String pendingSetupIntentId) {

        /**
         * SCA/3DS の事前認証が未解決か（設計書 §3.6・二段検証の判定）。
         *
         * @return {@code pending_setup_intent} が残っていれば true
         */
        public boolean hasPendingSetupIntent() {
            return pendingSetupIntentId != null && !pendingSetupIntentId.isBlank();
        }
    }
}
