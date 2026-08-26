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
     * 継続課金用の Stripe Price（{@code recurring}）を作成する（F08.9 P5・設計書 02 §4.1）。
     *
     * <p>{@link #createPrice} は一回払いの Price を作る。継続課金の Subscription には
     * {@code recurring.interval} を持つ Price が必要なため、課金周期（MONTHLY/YEARLY）を渡して別メソッドで作成する。</p>
     *
     * @param stripeProductId  Stripe Product ID
     * @param amount           金額
     * @param currency         通貨コード（ISO 4217）
     * @param billingInterval  課金周期（MONTHLY/YEARLY）
     * @return Stripe Price ID（{@code price_xxx}・{@code recurring}）
     */
    String createRecurringPrice(String stripeProductId, BigDecimal amount, String currency,
                                com.mannschaft.app.payment.BillingInterval billingInterval);

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
     * Destination Charge の PaymentIntent を作成し、保存済み PaymentMethod で<b>server-side off-session 即時確定</b>する
     * （F08.9 P5 継続課金の初回会費・R2-1 根治・設計書 02 §4.1）。
     *
     * <p>{@link #createDestinationPaymentIntent} は PaymentMethod 無し・未 confirm の PI を作るため、FE が
     * カード入力で on-session confirm する P1 では機能するが、P5 の継続課金は「保存済み既定 PM での off-session 課金」
     * であり FE は confirm しない。本メソッドは {@code setPaymentMethod(pm)}＋{@code setConfirm(true)}＋
     * {@code setOffSession(true)} を加えて<b>サーバ側で即時確定</b>する。{@code transfer_data.destination}・
     * {@code on_behalf_of}・{@code application_fee_amount}・{@code capture_method} は {@link #createDestinationPaymentIntent}
     * と同一（会費は {@link CaptureMethod#AUTOMATIC}）。確定が成功すると Stripe から {@code payment_intent.succeeded}
     * webhook が飛び、既存 {@link com.mannschaft.app.payment.escrow.EscrowWebhookService} 経路で AUTHORIZED→CAPTURED 化＋
     * 複式記帳が行われる（escrow は呼び出し側で AUTHORIZED 起票・二重記帳しない・P1 流儀と整合）。</p>
     *
     * <p><b>3DS/カード拒否（症状を隠さない・R2-1）:</b> off-session confirm が {@code authentication_required}
     * （off-session では 3DS 実行不能）/{@code card_declined} 等で失敗した場合、Stripe 側で確定した PI が残らないよう
     * 当該 PI を cancel（孤児を作らない）したうえで {@link OffSessionConfirmationException} を投げる。呼び出し側は
     * これを専用業務エラー（MEMBERSHIP_BILLING_023・402）へ変換し、Stripe 例外を握り潰さない。</p>
     *
     * @param chargeAmountMinor    課金額（最小通貨単位の整数・額面+支払手数料）
     * @param currency             通貨コード（ISO 4217・例 {@code "jpy"}）
     * @param payerCustomerId      支払者の Stripe Customer ID（{@code cus_xxx}）
     * @param applicationFeeMinor  Mannschaft 徴収手数料（最小通貨単位の整数）
     * @param destinationAccountId 受取側 Connect アカウント ID（{@code acct_xxx}）
     * @param captureMethod        capture 方式（会費は AUTOMATIC）
     * @param paymentMethodId      off-session 確定に用いる保存済み PaymentMethod（{@code pm_xxx}）
     * @param idempotencyKey       冪等性キー（設計書 02 §9）
     * @return 確定後の PaymentIntent 情報（id / clientSecret / status＝通常 {@code succeeded}）
     * @throws OffSessionConfirmationException off-session confirm がカード認証要求/拒否で失敗した場合（PI は cancel 済み）
     */
    PaymentIntentInfo createAndConfirmDestinationPaymentIntent(long chargeAmountMinor, String currency,
                                                              String payerCustomerId, long applicationFeeMinor,
                                                              String destinationAccountId, CaptureMethod captureMethod,
                                                              String paymentMethodId, String idempotencyKey);

    /**
     * off-session の即時確定（{@link #createAndConfirmDestinationPaymentIntent}）が、カードの追加認証要求
     * （{@code authentication_required}）またはカード拒否（{@code card_declined} 等）で成立しなかったことを表す
     * 非チェック例外（R2-1）。
     *
     * <p>{@code stripeErrorCode} は Stripe のエラーコード（例 {@code authentication_required}）。確定前の
     * PaymentIntent はプロバイダ側で cancel 済み（孤児を残さない）。呼び出し側はこれを業務エラー
     * （MEMBERSHIP_BILLING_023・402）へ変換し、症状を隠さず払い手へ「カード再認証/別カード登録」を促す。</p>
     */
    class OffSessionConfirmationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient String stripeErrorCode;

        public OffSessionConfirmationException(String stripeErrorCode, String message, Throwable cause) {
            super(message, cause);
            this.stripeErrorCode = stripeErrorCode;
        }

        /** Stripe のエラーコード（例 {@code authentication_required}/{@code card_declined}）。null 可。 */
        public String getStripeErrorCode() {
            return stripeErrorCode;
        }
    }

    /**
     * manual-capture の PaymentIntent を確定（capture）する（設計書 02 §5.3 / §8）。
     *
     * <p>{@code capture_method='manual'} で与信済み（{@code requires_capture}）の PaymentIntent を確定する。
     * capture と同時に {@code transfer_data.destination} への送金（{@code application_fee_amount} 控除後）が
     * 起こり、Mannschaft は資金を保持しない（Destination Charge・README §1.0）。</p>
     *
     * <p>{@code idempotencyKey="capture-{escrowId}"} を渡し、ネットワーク再送でも二重 capture を Stripe 側で
     * 拒否する（札行 PESSIMISTIC_WRITE ロックとの二重防御・設計書 02 §5.3）。返り値は確定後の
     * {@link PaymentIntentInfo}（{@code status} は通常 {@code succeeded}）。</p>
     *
     * @param paymentIntentId 対象 PaymentIntent ID（{@code pi_xxx}・{@code requires_capture}）
     * @param idempotencyKey  冪等性キー（{@code capture-{escrowId}}・設計書 02 §9）
     * @return capture 後の PaymentIntent 情報（id / clientSecret / status）
     */
    PaymentIntentInfo captureManualPaymentIntent(String paymentIntentId, String idempotencyKey);

    /**
     * manual-capture の PaymentIntent を<b>部分額</b>で確定（capture）する
     * （F03.11.1 募集キャンセル料の徴収・設計書 §4.2 / §4.3 / §3.5.3）。
     *
     * <p>与信額のうち {@code amountToCaptureMinor} だけを確定し、残額は Stripe が自動的に解放する
     * （部分キャプチャの残額自動解放・§4.1-1）。解放のための API を別途呼ぶ必要はない。
     * 部分キャプチャ後に差額を追加キャプチャすることはできないため、キャプチャは 1 回で決め切る（§4.1-2）。</p>
     *
     * <p>{@code applicationFeeAmountMinor} を渡すと Capture 時に運営手数料を明示的に上書きする
     * （{@code A_eff = min(A, F)}・§3.5.3）。{@code null} を渡した場合は PaymentIntent 作成時の額のまま。</p>
     *
     * <p>既存の 2 引数版（全額キャプチャ）はシグネチャを変えずに残す（呼び出し元を壊さない・§4.2）。</p>
     *
     * @param paymentIntentId           対象 PaymentIntent ID（{@code pi_xxx}・{@code requires_capture}）
     * @param amountToCaptureMinor      確定する額（最小通貨単位・与信額以下であること・§4.1-3）
     * @param applicationFeeAmountMinor 上書きする運営手数料（最小通貨単位・{@code null} なら上書きしない）
     * @param idempotencyKey            冪等性キー（{@code canfee-{cancellationRecordId}}・§7.1）
     * @return capture 後の PaymentIntent 情報（id / clientSecret / status）
     */
    PaymentIntentInfo captureManualPaymentIntent(
            String paymentIntentId, long amountToCaptureMinor,
            Long applicationFeeAmountMinor, String idempotencyKey);

    /**
     * 既存 PaymentIntent を retrieve し、支払者本人へ返すための {@code clientSecret} を取得する
     * （F22.1 第二陣・札主の決済確認 EP・設計書 02 §1 行#8 / 03 §1）。
     *
     * <p>謝礼の与信（{@link #createDestinationPaymentIntent}）は応募成立リスナが事前起票するため、札主の
     * 決済確認画面（同期 GET）が {@code clientSecret} を後から取得するには、escrow が保持する PaymentIntent ID
     * から Stripe で {@code PaymentIntent.retrieve} して {@code client_secret} を引く必要がある（escrow には
     * {@code client_secret} を保存しない・PCI SAQ-A・03 §1）。返り値の {@code clientSecret} は<b>支払者本人のみ</b>へ
     * 返し、ログに出さない（03 §10）。</p>
     *
     * <p>{@code status} も併せて返し、呼び出し側が「未 confirm（{@code requires_confirmation}/
     * {@code requires_action}）か、既に与信確定（{@code requires_capture}）か、capture 済み（{@code succeeded}）か」を
     * 判断できるようにする（与信確定後に確認画面を出さないなどの分岐）。Stripe 通信失敗は症状を隠さず
     * {@link com.mannschaft.app.payment.connect.ConnectPaymentErrorCode#STRIPE_API_ERROR}（500）で投げる。</p>
     *
     * @param paymentIntentId 対象 PaymentIntent ID（{@code pi_xxx}）
     * @return PaymentIntent 情報（id / clientSecret / status）
     */
    PaymentIntentInfo retrievePaymentIntentClientSecret(String paymentIntentId);

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
     * Connect（Destination Charge）の返金を実行する（設計書 02 §6.1・設定A）。
     *
     * <p>capture 済みの謝礼/会費を返金する。{@code reverse_transfer=true} で<b>返金原資を受取側 Connect
     * 残高から戻す</b>ため Mannschaft は立替・自社負担しない。{@code refund_application_fee=false} で
     * <b>徴収済み Mannschaft 手数料は返金しない</b>（設定A・マスター確定）。Stripe 決済手数料（≈3.6%）は
     * Stripe 仕様上そもそも返らない（規約・決済画面で事前周知済・03 §10）。</p>
     *
     * <p>金を動かすのは Stripe であり、Mannschaft 側は自前の逆仕訳を作らない（{@code refunds}/{@code ledger_entries}
     * は記録・監査のみ・設計書 02 §6.1）。{@code idempotencyKey="refund-{escrowId}-{seq}"} で部分返金の連番ごとに
     * 二重返金を Stripe 側でも拒否する（設計書 02 §9・既存全額 {@link #createRefund(String, Long, Long)} とは
     * 別メソッドで非破壊に追加）。</p>
     *
     * <p><b>支払者負担モデル（マスター確定・2026-06-03 改訂）:</b> 全額返金で支払者へ戻す額は
     * <b>受取側が実際に受け取った正味＝transferAmount（{@code amount − application_fee}）</b>であり、
     * 支払者上乗せ手数料（2.5%）は戻らない。Mannschaft±0・受取側±0 を同時に満たすため
     * <b>{@code reverse_transfer=false}</b>（比例 reverse の取りこぼしを避ける）で支払者へ {@code amountMinor}
     * を返金し、送金の巻き戻しは {@link #reverseTransfer} で<b>明示的に同額</b>行う（decouple 方式）。
     * {@code refund_application_fee=false}（1.4% keep）は維持する。比例 reverse（{@code reverse_transfer=true}）
     * では送金の巻き戻し額が返金額と一致せず Mannschaft が持ち出しになるため採用しない（設計書 02 §6.1）。</p>
     *
     * @param paymentIntentId      返金対象 PaymentIntent ID（{@code pi_xxx}・capture 済み）
     * @param amountMinor          支払者へ戻す返金額（最小通貨単位・transferAmount ベースの部分/全額）
     * @param reason               返金理由（{@code requested_by_customer}/{@code duplicate}/{@code fraudulent} 等）
     * @param reverseTransfer      受取側 Connect 残高から比例 reverse するか（支払者負担モデルでは {@code false}）
     * @param refundApplicationFee 徴収済み application_fee を返金するか（設定A では {@code false}）
     * @param idempotencyKey       冪等性キー（{@code refund-{escrowId}-{seq}}・設計書 02 §9）
     * @return Connect 返金情報（refundId / status）
     */
    ConnectRefundInfo createConnectRefund(String paymentIntentId, long amountMinor, String reason,
                                          boolean reverseTransfer, boolean refundApplicationFee,
                                          String idempotencyKey);

    /**
     * Destination Charge の PaymentIntent に紐づく Stripe Transfer ID（{@code tr_xxx}）を解決する
     * （支払者負担モデルの decouple 返金・設計書 02 §6.1）。
     *
     * <p>capture（Destination Charge）時に受取側 Connect 口座へ送られた送金（Transfer）の ID を
     * {@code PaymentIntent → latest_charge → charge.transfer} の経路で取得する。送金が存在しない
     * （未 capture / transfer_data 未設定など）場合は {@code null} を返す。</p>
     *
     * @param paymentIntentId 対象 PaymentIntent ID（{@code pi_xxx}・capture 済み）
     * @return Stripe Transfer ID（{@code tr_xxx}）。解決不能なら {@code null}
     */
    String resolveTransferIdFromPaymentIntent(String paymentIntentId);

    /**
     * {@link #retrieveChargeProcessingFee} が「balance_transaction が未確定（{@code pending}）で実手数料を
     * まだ取得できない」ことを表す番兵値（{@code -1}・F22.1 §6.3 第二陣 C1）。
     *
     * <p>capture 後の charge は通常即座に balance_transaction が確定（{@code available}）し正の {@code fee} が
     * 立つが、返金直後や少額・特殊カードでごく稀に確定が遅延する。その場合に 0（手数料ゼロ）と誤認すると
     * 未回収残高を取りこぼすため、0 と明確に区別できる負の番兵を返す（症状を隠さない・後続バッチで補完可能）。
     * 呼び出し側は本値を検知したら残高計上をスキップしリコンシリエーション（§6.3）に委ねる。</p>
     */
    long PROCESSING_FEE_PENDING = -1L;

    /**
     * 元 charge（Destination Charge）の Stripe 決済手数料（{@code balance_transaction.fee}・minor・正値）を取得する
     * （F22.1 §6.3 第二陣 C1・ModeB 返金の真値台帳化）。
     *
     * <p>ModeB 返金では支払者へ満額返金し {@code refund_application_fee:true} で application_fee を返金するため、
     * <b>Stripe は元取引の決済手数料（≈369・minor）を返さない</b>。これが Mannschaft の真の一時負担額であり、
     * 受取側（payee）から回収すべき額である。{@code PaymentIntent.retrieve(id, expand=[latest_charge.balance_transaction])}
     * で展開した {@code charge.balance_transaction.fee} を返す（Destination Charge は platform 上に balance_transaction が
     * 立つため platform 文脈の retrieve でよい）。</p>
     *
     * <p><b>未確定（pending）の扱い（症状を隠さない・正直設計）:</b> balance_transaction がまだ確定していない
     * （{@code status='pending'}）／latest_charge・balance_transaction が解決できない場合は、0（手数料ゼロ）と
     * 誤認させず {@link #PROCESSING_FEE_PENDING}（{@code -1}）を返す。呼び出し側は残高計上をスキップし、後続の
     * リコンシリエーション（§6.3）で補完する。Stripe 通信失敗は握り潰さず
     * {@link com.mannschaft.app.payment.connect.ConnectPaymentErrorCode#STRIPE_API_ERROR}（500）で上申する。</p>
     *
     * @param paymentIntentId 元取引の PaymentIntent ID（{@code pi_xxx}・capture 済み）
     * @return Stripe 決済手数料（minor・正値）。未確定/未取得なら {@link #PROCESSING_FEE_PENDING}（{@code -1}）
     */
    long retrieveChargeProcessingFee(String paymentIntentId);

    /**
     * 受取側 Connect 口座への送金を<b>明示的に</b>巻き戻す（{@code TransferReversal}・支払者負担モデル・設計書 02 §6.1）。
     *
     * <p>{@link #createConnectRefund}（{@code reverse_transfer=false}）で支払者へ返金した額と<b>同額</b>を
     * 受取側送金から巻き戻すことで「Mannschaft±0」「受取側±0（受け取った分だけ戻す）」を同時達成する
     * （比例 reverse の取りこぼし回避）。{@code idempotency_key} で再送時の二重巻き戻しを Stripe 側でも拒否する。</p>
     *
     * @param transferId     対象 Stripe Transfer ID（{@code tr_xxx}・{@link #resolveTransferIdFromPaymentIntent} で解決）
     * @param amountMinor    巻き戻し額（最小通貨単位・支払者へ戻す額と同額）
     * @param idempotencyKey 冪等性キー（{@code reversal-{escrowId}-{seq}}・設計書 02 §9）
     */
    void reverseTransfer(String transferId, long amountMinor, String idempotencyKey);

    // ========================================
    // F08.9 P5 継続課金（SetupIntent 基盤＋Subscription・設計書 02 §4.1。既存メソッドは破壊しない追加）
    // ========================================

    /**
     * off_session 再利用用の SetupIntent を作成する（設計書 F08.9 02 §4.1）。
     *
     * <p>継続課金（案b）は次サイクル以降を off_session（カード保持者不在）で課金するため、加入前に
     * SetupIntent でカードを保存する。{@code usage=off_session} で「将来の自動課金に使う PM」として登録する。
     * 返り値 {@code clientSecret} を払い手本人へ返し、FE が Stripe.js で confirm（カード直送・PCI SAQ-A・03 §1）する。</p>
     *
     * @param customerId 払い手の Stripe Customer ID（{@code cus_xxx}）
     * @return SetupIntent 情報（id / clientSecret / status）
     */
    SetupIntentInfo createSetupIntent(String customerId);

    /**
     * confirm 済みの PaymentMethod を Customer に attach し、既定（invoice の default_payment_method）に設定する
     * （設計書 F08.9 02 §4.1）。
     *
     * <p>FE で SetupIntent を confirm して得た {@code payment_method_id} を、(1) {@link com.stripe.model.PaymentMethod#attach}
     * で Customer に紐付け、(2) {@code Customer.update(invoice_settings.default_payment_method=pm)} で既定に設定する。
     * これにより次サイクルの Subscription invoice が off_session で本 PM を使う。</p>
     *
     * @param customerId      払い手の Stripe Customer ID（{@code cus_xxx}）
     * @param paymentMethodId confirm 済みの PaymentMethod ID（{@code pm_xxx}）
     */
    void attachPaymentMethodAndSetDefault(String customerId, String paymentMethodId);

    /**
     * 継続課金の Stripe Subscription を作成する（案b・次サイクル開始・設計書 02 §4.1）。
     *
     * <p><b>初回会費は本メソッドの外で単発 destination charge（P1 同型）で徴収済み</b>であり、Subscription は
     * {@code billing_cycle_anchor=次サイクル開始（unix 秒）}＋{@code proration_behavior=NONE} で起動するため
     * <b>初回 invoice を発生させない</b>（PoC 実証 2026-06-05・§4.1）。これにより以降の全 invoice が更新型
     * （{@code subscription_cycle}）となり draft 窓の固定手数料上書きが全サイクルで正確に通る。</p>
     *
     * <p>{@code transfer_data.destination}＝受領者 Connect 口座・{@code on_behalf_of}＝同口座・
     * {@code default_payment_method}＝保存済み PM・{@code application_fee_percent}＝安全側既定（invoice 上書きが正・
     * 第四波 webhook が {@code fee_policy_key} で固定額へ上書き）。{@code payment_behavior=ALLOW_INCOMPLETE} で
     * 初回 invoice なしの作成を許容する。</p>
     *
     * <p><b>複数明細（案C・手数料折半の根治）:</b> {@code priceIds} には「会費 Price（額面）」と
     * 「支払側手数料 Price（{@code FeeBreakdown.payerFee}）」を渡し、invoice 合計を初回サイクルの
     * PaymentIntent 金額（{@code chargeAmount}）と一致させる。{@code payerFee == 0} の契約では
     * 会費 Price のみの 1 要素になる。</p>
     *
     * @param customerId               払い手の Stripe Customer ID（{@code cus_xxx}）
     * @param priceIds                 継続課金の Stripe Price ID 群（{@code price_xxx}・会費＋任意で手数料・1 要素以上）
     * @param defaultPaymentMethodId   off_session 課金に使う PaymentMethod ID（{@code pm_xxx}）
     * @param destinationAccountId     受領者 Connect アカウント ID（{@code acct_xxx}）
     * @param applicationFeePercent    application_fee の率（安全側既定・invoice 上書きが正）
     * @param billingCycleAnchorEpochSec 次サイクル開始の unix 秒（この時刻に最初の課金 invoice が発生）
     * @param idempotencyKey           冪等性キー（設計書 02 §9）
     * @return Subscription 情報（id / status / currentPeriodEnd）
     */
    SubscriptionInfo createSubscription(String customerId, java.util.List<String> priceIds,
                                        String defaultPaymentMethodId,
                                        String destinationAccountId, java.math.BigDecimal applicationFeePercent,
                                        long billingCycleAnchorEpochSec, String idempotencyKey);

    /**
     * 継続課金の platform Webhook イベント（{@code invoice.*} / {@code customer.subscription.deleted}）を検証・パースする
     * （F08.9 P5 第三波・設計書 02 §4.2）。
     *
     * <p>platform 署名シークレット（{@link #constructEvent} と同一）で検証する。継続課金の Subscription は
     * platform 上に作成されるため、各サイクルの invoice 系イベント・解約イベントは platform Webhook で届く。
     * {@code eventId}（冪等キー）・{@code subscriptionId}（{@code membership_subscriptions} 逆引きキー）・
     * {@code billingReason}（{@code subscription_cycle}/{@code subscription_create} 判定）・{@code invoiceStatus}
     * （{@code draft} 窓判定）・{@code invoiceId}（上書き対象）・{@code paymentIntentId}/{@code chargeId}（記帳の突合）・
     * 現サイクル期間（{@code periodStartEpochSec}/{@code periodEndEpochSec}）を含む専用 record を返す。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     * @return 継続課金 Webhook イベント情報
     */
    InvoiceWebhookEventInfo constructInvoiceEvent(String payload, String sigHeader);

    /**
     * draft 状態の invoice の {@code application_fee_amount} を固定円で上書きする（F08.9 P5 第三波・★核心・設計書 02 §4.2）。
     *
     * <p>継続課金の各サイクル invoice は subscription の {@code application_fee_percent} で率手数料が自動計算される。
     * {@code invoice.created}（draft 窓）でこの率を固定円に<b>上書き</b>することで、{@code fee_policy_key} で焼き付けた
     * 固定手数料を全サイクルで正確に徴収する（PoC 実証 2026-06-05）。{@code POST /v1/invoices/{id}} を
     * {@code application_fee_amount} 付きで呼ぶ。<b>SDK バージョン固定条件あり</b>（Stripe API {@code 2025-02-24.acacia}／
     * stripe-java 28.2.0。basil 系ではこのフィールドが invoice に存在せず黙殺されるため、29.x 以降へ上げる際は機構再設計が必要・
     * README §4.4）。上書きが失敗した場合は症状を隠さず例外を投げ、呼び出し側が Stripe 再送に委ねる。</p>
     *
     * @param invoiceId            上書き対象 invoice ID（{@code in_xxx}・draft）
     * @param applicationFeeMinor  固定 application_fee（最小通貨単位・{@code fee_policy} 算出値）
     * @param idempotencyKey       冪等性キー（設計書 02 §9）
     */
    void updateInvoiceApplicationFee(String invoiceId, long applicationFeeMinor, String idempotencyKey);

    /**
     * 継続課金 platform Webhook イベント情報（F08.9 P5 第三波・設計書 02 §4.2）。
     *
     * <p>{@code eventId} は冪等キー（{@code evt_xxx}）。{@code subscriptionId} で {@code membership_subscriptions} を
     * 逆引きする。{@code invoice.*} 系では invoice の各フィールドを格納し、{@code customer.subscription.deleted} では
     * {@code subscriptionId} のみ（他は null）。</p>
     *
     * @param eventId              Stripe イベント ID（{@code evt_xxx}・冪等キー）
     * @param type                 イベント種別（{@code invoice.created}/{@code invoice.paid}/{@code invoice.payment_failed}/
     *                             {@code customer.subscription.deleted}）
     * @param livemode             本番/テスト区分
     * @param subscriptionId       Stripe Subscription ID（{@code sub_xxx}・逆引きキー）
     * @param invoiceId            invoice ID（{@code in_xxx}・{@code invoice.*} のみ）
     * @param invoiceStatus        invoice 状態（{@code draft}/{@code open}/{@code paid} 等・上書き窓判定）
     * @param billingReason        課金理由（{@code subscription_cycle}/{@code subscription_create} 等・対象判定）
     * @param amountPaidMinor      支払済額（最小通貨単位・{@code invoice.paid} の記帳元）
     * @param paymentIntentId      invoice に紐づく PaymentIntent ID（{@code pi_xxx}・記帳突合）
     * @param chargeId             invoice に紐づく Charge ID（{@code ch_xxx}・記帳突合）
     * @param periodStartEpochSec  現サイクル開始 unix 秒（null 可）
     * @param periodEndEpochSec    現サイクル終了 unix 秒（valid_until 延長元・null 可）
     */
    record InvoiceWebhookEventInfo(String eventId, String type, boolean livemode,
                                   String subscriptionId, String invoiceId, String invoiceStatus,
                                   String billingReason, Long amountPaidMinor,
                                   String paymentIntentId, String chargeId,
                                   Long periodStartEpochSec, Long periodEndEpochSec) {}

    /**
     * 継続課金の Stripe Subscription を今月スキップする（{@code pause_collection・behavior=void}・設計書 02 §4.3）。
     *
     * <p>{@code pause_collection={behavior:'void', resumes_at:<unix_sec>}} を設定し、スキップ月の invoice を void 化する。
     * void 化により {@code invoice.paid} は発火せず {@code valid_until} は延びない（ペイウォール無改修で整合・README §4.5）。
     * {@code resumes_at} は再開予定日（{@code current_period_end + 1 billing_interval} で計算・呼出側が算出して渡す）。</p>
     *
     * @param subscriptionId   対象 Stripe Subscription ID（{@code sub_xxx}）
     * @param resumesAtEpochSec 再開予定日の unix 秒（pause_collection.resumes_at）
     * @param idempotencyKey   冪等性キー（設計書 02 §9）
     */
    void pauseSubscriptionCollection(String subscriptionId, long resumesAtEpochSec, String idempotencyKey);

    /**
     * 継続課金の Stripe Subscription のスキップ（{@code pause_collection}）を解除して再開する（設計書 02 §4.3）。
     *
     * <p>{@code SubscriptionUpdateParams.pauseCollection(EmptyParam.EMPTY)} で pause_collection を明示的に解除する
     * （{@code null} セット）。次サイクルから通常課金が再開される。</p>
     *
     * @param subscriptionId 対象 Stripe Subscription ID（{@code sub_xxx}）
     * @param idempotencyKey 冪等性キー（設計書 02 §9）
     */
    void resumeSubscriptionCollection(String subscriptionId, String idempotencyKey);

    /**
     * Stripe Subscription を期末解約予約する（{@code cancel_at_period_end=true}・設計書 02 §4.1）。
     *
     * <p>期末まで利用可・日割り返金なし・期末前は再有効化可。即時解約はしない（README §4.1）。
     * 返り値で現サイクル終了（{@code current_period_end}）を返し、応答に「○月○日まで利用可」を明示するため使う。</p>
     *
     * @param subscriptionId 対象 Stripe Subscription ID（{@code sub_xxx}）
     * @param idempotencyKey 冪等性キー（設計書 02 §9）
     * @return Subscription 情報（id / status / currentPeriodEnd）
     */
    SubscriptionInfo cancelSubscriptionAtPeriodEnd(String subscriptionId, String idempotencyKey);

    /**
     * SetupIntent 情報（設計書 F08.9 02 §4.1）。
     *
     * <p>{@code clientSecret} は払い手本人のみへ返す（他人へ漏らさない・03 §1）。</p>
     */
    record SetupIntentInfo(String setupIntentId, String clientSecret, String status) {}

    /**
     * Stripe Subscription 情報（設計書 F08.9 02 §4.1）。
     *
     * <p>{@code currentPeriodEnd} は現サイクル終了の unix 秒（解約応答の「○月○日まで利用可」算出に用いる・null 可）。</p>
     */
    record SubscriptionInfo(String subscriptionId, String status, Long currentPeriodEnd) {}

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
     * 与信系 platform Webhook イベント情報（設計書 02 §4.2 / §6.1）。
     *
     * <p>{@code eventId} は冪等キー（{@code evt_xxx}）。{@code paymentIntentId}/{@code paymentIntentStatus}
     * で対象 escrow を特定し状態確定する。</p>
     *
     * <p>{@code charge.refunded}（設計書 02 §6.1）では Charge の {@code payment_intent} を
     * {@code paymentIntentId} に、最新の Refund を {@code refundId} に、当該 Refund 額と Charge 総額を
     * {@code refundedAmountMinor}/{@code chargeAmountMinor} に格納する（{@code payment_intent.*} 系では
     * これら refund フィールドは null）。全額/部分の判定と {@code refunds} 行の確定に用いる。</p>
     */
    record EscrowWebhookEventInfo(String eventId, String type, boolean livemode,
                                  String paymentIntentId, String paymentIntentStatus,
                                  String refundId, Long refundedAmountMinor, Long chargeAmountMinor) {}

    /**
     * Connect 返金情報（設計書 02 §6.1・設定A）。
     *
     * <p>{@code refundId} は {@code re_xxx}（{@code refunds.stripe_refund_id} UNIQUE）。{@code status} は
     * Stripe の Refund ステータス（{@code pending}/{@code succeeded} 等）。確定は {@code charge.refunded}
     * Webhook で行うため、本 record は INSERT 時の {@code stripe_refund_id} 記録に用いる。</p>
     */
    record ConnectRefundInfo(String refundId, String status) {}

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

    // ========================================
    // F20.1 実決済（自社受取×月額サブスク・D-1〜D-4・2026-07-10 御裁可。既存メソッドは破壊しない追加）
    // ========================================

    /**
     * 月額サブスクの Stripe Checkout Session を作成する（{@code Mode.SUBSCRIPTION}・<b>Connect 不使用</b>・設計書 02）。
     *
     * <p>F08.9 会費（Connect destination charge）と異なり、{@code transfer_data}/{@code on_behalf_of}/
     * {@code application_fee} を<b>一切含めない</b>（自社受取・D-2）。Price は毎月の recurring をインライン
     * {@code price_data}（{@code product_data.name}＝表示名・{@code unit_amount}＝円額・{@code recurring.interval=month}）で
     * 生成する（F09.13 の Product/Price 遅延生成の思想を Checkout インライン化）。{@code metadata.billingContractId} に
     * 契約 ID を焼き付け、{@code checkout.session.completed} webhook で PENDING→ACTIVE を突合する。</p>
     *
     * @param stripeCustomerId 決済者の Stripe Customer ID（{@code cus_xxx}・get-or-create 済み）
     * @param priceJpy         月額（円・ゼロ decimal 通貨のため乗算不要）
     * @param productName      Stripe Product 表示名（プラン/機能の表示名）
     * @param billingContractId {@code billing_contracts.id}（{@code metadata.billingContractId}）
     * @param successUrl       決済成功時の遷移先
     * @param cancelUrl        決済中断時の遷移先
     * @return Checkout Session 情報（sessionId / checkoutUrl / expiresAt）
     */
    CheckoutSessionInfo createBillingSubscriptionCheckoutSession(
            String stripeCustomerId, long priceJpy, String productName, String billingContractId,
            String successUrl, String cancelUrl);

    /**
     * Stripe Subscription を<b>即時解約</b>する（F20.1 実決済・退会 purge 連動 AC-45）。
     *
     * <p>{@link #cancelSubscriptionAtPeriodEnd} の期末解約と異なり、その場で subscription を cancel する
     * （退会確定＝purge 後のユーザーへの課金継続を止める・日割り返金なし）。失敗は
     * {@code STRIPE_API_ERROR} で上申し、呼び出し側（purge リスナー）が ERROR ログで手動照合に委ねる
     * （症状を隠さない）。</p>
     *
     * @param subscriptionId 対象 Stripe Subscription ID（{@code sub_xxx}）
     * @param idempotencyKey 冪等性キー（{@code billing-purge-{subscriptionId}}）
     */
    void cancelBillingSubscriptionImmediately(String subscriptionId, String idempotencyKey);

    /**
     * F20.1 実決済の Webhook イベントを検証・パースする（{@code checkout.session.completed}/{@code .expired}・
     * {@code invoice.paid}/{@code invoice.payment_failed}・{@code customer.subscription.deleted}）。
     *
     * <p>platform 署名シークレット（{@link #constructEvent} と同一）で検証する。billing 固有の突合に必要な
     * {@code billingContractId}（session.metadata）・{@code subscriptionId}（逆引きキー）・{@code customerId}・
     * {@code currentPeriodEndEpochSec}（valid_until 延長/失効時刻）を抽出した専用 record を返す。既存の
     * {@code WebhookEventInfo}/{@code InvoiceWebhookEventInfo} は billing 固有フィールドを持たないため専用パースを設ける。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     * @return billing 決済イベント情報
     */
    BillingSubscriptionWebhookEventInfo constructBillingSubscriptionEvent(String payload, String sigHeader);

    /**
     * F20.1 実決済 Webhook イベント情報（設計書 02）。
     *
     * <p>{@code eventId} は冪等キー（{@code evt_xxx}）。{@code checkout.session.*} では {@code sessionId}/
     * {@code billingContractId}（metadata）/{@code subscriptionId}/{@code customerId} を、{@code invoice.*} /
     * {@code customer.subscription.deleted} では {@code subscriptionId}/{@code currentPeriodEndEpochSec} を格納する
     * （非該当フィールドは null）。</p>
     *
     * @param eventId                  Stripe イベント ID（{@code evt_xxx}・冪等キー）
     * @param type                     イベント種別
     * @param livemode                 本番/テスト区分
     * @param sessionId                Checkout Session ID（{@code cs_xxx}・{@code checkout.session.*} のみ）
     * @param billingContractId        {@code session.metadata.billingContractId}（billing 所有判定・{@code checkout.session.*} のみ）
     * @param subscriptionId           Stripe Subscription ID（{@code sub_xxx}・逆引きキー）
     * @param customerId               Stripe Customer ID（{@code cus_xxx}・焼付用・{@code checkout.session.completed} のみ）
     * @param currentPeriodEndEpochSec 現サイクル終了の unix 秒（valid_until 延長/失効時刻・null 可）
     */
    record BillingSubscriptionWebhookEventInfo(
            String eventId, String type, boolean livemode,
            String sessionId, String billingContractId, String subscriptionId, String customerId,
            Long currentPeriodEndEpochSec) {}
}
