package com.mannschaft.app.payment.stripe;

import java.math.BigDecimal;

/**
 * Stripe 決済プロバイダーインターフェース。
 * <p>
 * Stripe SDK への依存を抽象化し、テスト時にモック差し替えを可能にする。
 */
public interface StripePaymentProvider {

    /**
     * Stripe Product を作成する。
     *
     * @param name          商品名
     * @param paymentItemId 支払い項目 ID（metadata 用）
     * @return Stripe Product ID（prod_xxxxxxxxxx）
     */
    String createProduct(String name, Long paymentItemId);

    /**
     * Stripe Price を作成する。
     *
     * @param stripeProductId Stripe Product ID
     * @param amount          金額
     * @param currency        通貨コード（ISO 4217）
     * @return Stripe Price ID（price_xxxxxxxxxx）
     */
    String createPrice(String stripeProductId, BigDecimal amount, String currency);

    /**
     * Stripe Price をアーカイブ（非アクティブ化）する。
     *
     * @param stripePriceId Stripe Price ID
     */
    void archivePrice(String stripePriceId);

    /**
     * Stripe Product をアーカイブ（非アクティブ化）する。
     *
     * @param stripeProductId Stripe Product ID
     */
    void archiveProduct(String stripeProductId);

    /**
     * Stripe Price を取得し、金額と通貨を検証する。
     *
     * @param stripePriceId Stripe Price ID
     * @return Price 情報
     */
    PriceInfo retrievePrice(String stripePriceId);

    /**
     * Stripe Customer を作成する。
     *
     * @param email ユーザーのメールアドレス
     * @param userId ユーザー ID（metadata 用）
     * @return Stripe Customer ID（cus_xxxxxxxxxx）
     */
    String createCustomer(String email, Long userId);

    /**
     * Stripe Checkout Session を作成する（一回払い）。
     *
     * @param stripePriceId      Stripe Price ID
     * @param stripeCustomerId   Stripe Customer ID
     * @param memberPaymentId    支払い記録 ID（metadata 用）
     * @param successUrl         決済成功後の遷移先 URL
     * @param cancelUrl          決済キャンセル時の遷移先 URL
     * @return Checkout Session 情報
     */
    CheckoutSessionInfo createCheckoutSession(String stripePriceId, String stripeCustomerId,
                                              Long memberPaymentId, String successUrl, String cancelUrl);

    /**
     * 通知クレジット購入用 Stripe Checkout Session を作成する（一回払い）。
     *
     * <p>F09.13: メタデータに {@code notificationCreditPurchaseId} を含める。</p>
     *
     * @param stripePriceId                  Stripe Price ID
     * @param stripeCustomerId               Stripe Customer ID
     * @param notificationCreditPurchaseId   通知クレジット購入ID（metadata 用）
     * @param successUrl                     決済成功後の遷移先 URL
     * @param cancelUrl                      決済キャンセル時の遷移先 URL
     * @return Checkout Session 情報
     */
    CheckoutSessionInfo createNotificationCreditCheckoutSession(String stripePriceId, String stripeCustomerId,
                                                                Long notificationCreditPurchaseId,
                                                                String successUrl, String cancelUrl);

    /**
     * Stripe Refund（全額返金）を実行する。
     *
     * @param stripePaymentIntentId Stripe Payment Intent ID
     * @param memberPaymentId       支払い記録 ID（metadata 用）
     * @param refundedBy            返金操作者のユーザー ID（metadata 用）
     * @return Stripe Refund ID（re_xxxxxxxxxx）
     */
    String createRefund(String stripePaymentIntentId, Long memberPaymentId, Long refundedBy);

    /**
     * Stripe Checkout Session の状態を取得する（手動再同期用）。
     *
     * @param stripeCheckoutSessionId Stripe Checkout Session ID
     * @return Session の状態情報
     */
    SessionStatusInfo retrieveSessionStatus(String stripeCheckoutSessionId);

    /**
     * Stripe Webhook の署名を検証し、イベントペイロードをパースする。
     *
     * @param payload    生リクエストボディ
     * @param sigHeader  Stripe-Signature ヘッダー
     * @return パースされたイベント情報
     */
    WebhookEventInfo constructEvent(String payload, String sigHeader);

    // ========================================
    // F22.1 謝礼決済 Connect（P2-a・設計書 02 §8。既存メソッドは破壊しない追加）
    // ========================================

    /**
     * Stripe Connect Express アカウントを作成する（受領者の口座）。
     *
     * @param country   ISO 3166-1 alpha-2 国コード（例: {@code "JP"}）
     * @param scopeKind 受領主体の種別（USER/TEAM/ORG・metadata 用）
     * @param scopeId   受領主体の論理 ID（metadata 用）
     * @return Stripe Connect アカウント ID（{@code acct_xxx}）
     */
    String createConnectAccount(String country,
                                com.mannschaft.app.payment.connect.ScopeKind scopeKind,
                                Long scopeId);

    /**
     * Connect アカウントの hosted onboarding（account_onboarding）リンクを作成する。
     *
     * @param stripeAccountId Connect アカウント ID（{@code acct_xxx}）
     * @param returnUrl       onboarding 完了後の戻り URL
     * @param refreshUrl      リンク失効時の再発行 URL
     * @return AccountLink 情報（onboarding URL と失効時刻）
     */
    AccountLinkInfo createAccountLink(String stripeAccountId, String returnUrl, String refreshUrl);

    /**
     * Connect アカウントの最新状態を取得する（status 同期用）。
     *
     * @param stripeAccountId Connect アカウント ID（{@code acct_xxx}）
     * @return Connect アカウント状態
     */
    ConnectAccountInfo retrieveConnectAccount(String stripeAccountId);

    /**
     * Destination Charge の PaymentIntent を作成する（設計書 02 §5.1 / §8）。
     *
     * <p>{@code transfer_data.destination} ＋ {@code on_behalf_of} を受取側 Connect アカウントに設定し、
     * {@code application_fee_amount} で Mannschaft 手数料を控除する。{@code capture_method} は
     * {@link CaptureMethod#MANUAL}（謝礼・与信→後で capture）/ {@link CaptureMethod#AUTOMATIC}
     * （会費・即時 capture）で分岐する。返り値 {@code clientSecret} は支払者が Stripe.js で
     * confirm（カード直送・PCI SAQ-A）するために必要（設計書 03 §1）。</p>
     *
     * @param chargeAmountMinor   課金額（最小通貨単位の整数・額面+支払手数料）
     * @param currency            通貨コード（ISO 4217・例 {@code "jpy"}）
     * @param payerCustomerId     支払者の Stripe Customer ID（{@code cus_xxx}）
     * @param applicationFeeMinor Mannschaft 徴収手数料（最小通貨単位の整数）
     * @param destinationAccountId 受取側 Connect アカウント ID（{@code acct_xxx}）
     * @param captureMethod       capture 方式（MANUAL / AUTOMATIC）
     * @param idempotencyKey      冪等性キー（設計書 02 §9）
     * @return PaymentIntent 情報（id / clientSecret / status）
     */
    PaymentIntentInfo createDestinationPaymentIntent(long chargeAmountMinor, String currency,
                                                     String payerCustomerId, long applicationFeeMinor,
                                                     String destinationAccountId, CaptureMethod captureMethod,
                                                     String idempotencyKey);

    /**
     * 与信を取消す（capture 前の PaymentIntent.cancel・設計書 02 §6 / §8）。
     *
     * <p>札下げ / hold 失効 / 72h 猶予超過などで与信を取り消す。capture 後は対象外（返金で対応）。</p>
     *
     * @param paymentIntentId 対象 PaymentIntent ID（{@code pi_xxx}）
     * @param idempotencyKey  冪等性キー（{@code cancel-{escrowId}}・設計書 02 §9）
     */
    void cancelAuthorization(String paymentIntentId, String idempotencyKey);

    /**
     * 与信系（escrow）の platform Webhook イベントを検証・パースする（設計書 02 §4.2）。
     *
     * <p>platform 署名シークレット（{@link #constructEvent} と同一）で検証する。
     * {@code payment_intent.amount_capturable_updated}（与信確定）/{@code payment_intent.canceled}
     * （取消）/{@code payment_intent.succeeded}（capture・次Phase）を扱うため、{@code eventId}
     * （冪等キー）と PaymentIntent の {@code id}/{@code status} を含む専用 record を返す。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     * @return 与信系イベント情報
     */
    EscrowWebhookEventInfo constructEscrowEvent(String payload, String sigHeader);

    /**
     * Destination PaymentIntent 情報（設計書 02 §8）。
     *
     * <p>{@code clientSecret} は支払者本人のみへ返す（他人へ漏らさない・03 §1）。</p>
     */
    record PaymentIntentInfo(String paymentIntentId, String clientSecret, String status) {}

    /**
     * 与信系 platform Webhook イベント情報（設計書 02 §4.2）。
     *
     * <p>{@code eventId} は冪等キー（{@code evt_xxx}）。{@code paymentIntentId}/{@code paymentIntentStatus}
     * で対象 escrow を特定し状態確定する。</p>
     */
    record EscrowWebhookEventInfo(String eventId, String type, boolean livemode,
                                  String paymentIntentId, String paymentIntentStatus) {}

    /**
     * Connect Webhook の署名を検証し、イベントをパースする。
     *
     * <p>platform 用 {@link #constructEvent} と別の署名シークレット
     * （{@code mannschaft.stripe.connect-webhook-secret}）で検証する（設計書 03 §2）。
     * {@code account.updated} 等の Connect 固有イベントを扱うため専用 record を返す。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     * @return Connect イベント情報
     */
    ConnectWebhookEventInfo constructConnectEvent(String payload, String sigHeader);

    /**
     * Connect Webhook イベント情報。
     *
     * <p>{@code eventId} は冪等性キー（{@code evt_xxx}）。{@code stripeAccountId} は
     * {@code account.updated}/{@code account.application.deauthorized} の対象アカウント。
     * {@code requirementsDue} は KYC 要件不足項目。</p>
     */
    record ConnectWebhookEventInfo(String eventId, String type, boolean livemode,
                                   String stripeAccountId,
                                   boolean chargesEnabled, boolean payoutsEnabled,
                                   java.util.List<String> requirementsDue) {}

    /**
     * AccountLink（hosted onboarding 遷移リンク）情報。
     */
    record AccountLinkInfo(String url, java.time.LocalDateTime expiresAt) {}

    /**
     * Connect アカウント状態（{@code account.updated} Webhook / 同期取得用）。
     *
     * <p>{@code requirementsDue} は KYC 要件不足項目（RESTRICTED 時のみ非空）。</p>
     */
    record ConnectAccountInfo(boolean chargesEnabled, boolean payoutsEnabled,
                              java.util.List<String> requirementsDue) {}

    /**
     * Stripe Price 情報。
     */
    record PriceInfo(String priceId, String productId, BigDecimal unitAmount, String currency) {}

    /**
     * Checkout Session 情報。
     */
    record CheckoutSessionInfo(String sessionId, String checkoutUrl, java.time.LocalDateTime expiresAt) {}

    /**
     * Session 状態情報（手動再同期用）。
     */
    record SessionStatusInfo(String paymentStatus, String paymentIntentId, String paymentIntentStatus) {}

    /**
     * Webhook イベント情報。
     *
     * <p>{@code notificationCreditPurchaseId} は F09.13 通知クレジット購入のみセットされる。
     * {@code memberPaymentId} と排他利用（どちらか一方のみ null でない）。</p>
     */
    record WebhookEventInfo(String type, String sessionId, String paymentIntentId,
                            String memberPaymentId, String subscriptionId,
                            BigDecimal amountReceived, String receiptUrl, String refundId,
                            BigDecimal refundAmount, BigDecimal paymentIntentAmount,
                            Long notificationCreditPurchaseId) {}
}
