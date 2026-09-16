package com.mannschaft.app.billing;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Billing Center PR6b-1: プラン変更（upgrade）の Stripe 窓口（<b>試練A が置いた発注書</b>）。
 *
 * <p>既存の {@link BillingPaymentGateway} が「解約・引継・スナップショット取得」の窓口であるのに対し、
 * 本ポートは<b>プラン変更だけ</b>を扱う。実体は第7隊が {@code StripeBillingPaymentGateway} と同じ流儀で
 * {@code StripePaymentProvider} 経由に実装すること（Stripe SDK を直接触らない）。</p>
 *
 * <h2>なぜ別ポートにしたか</h2>
 * <p>試練は実装より先に書かれるため、テストが Stripe の戻り値を差し替えられなければ
 * 「Stripe が返した値がそのまま出る」（AC-2）も「always_invoice ＋ pending_if_incomplete で呼ぶ」（AC-29）も
 * 観測できない。既存 {@link BillingPaymentGateway} へ default メソッドを足す手もあるが、同じ worktree で
 * 試練B（3DS）が同ファイルを触っており、衝突すると両隊の red が同時に壊れる。関心の分離としても
 * プラン変更は独立しているため、新しいポートとして切り出した。</p>
 *
 * <p><b>第7隊への申し送り</b>: このポート名・引数を変えるなら、{@code BillingChangePreviewApiRedIT} /
 * {@code BillingPlanUpgradeApiRedIT} の {@code @MockitoBean} も同時に移すこと。移さずに名前だけ変えると
 * 「Stripe を呼んでいないのに緑」という空虚な緑になる。</p>
 */
public interface BillingPlanChangeGateway {

    /** AC-29: 差額を必ず即時請求する（Stripe の {@code proration_behavior}）。 */
    String PRORATION_BEHAVIOR_ALWAYS_INVOICE = "always_invoice";

    /** AC-29: 支払いが完了しなければ適用を保留する（Stripe の {@code payment_behavior}）。 */
    String PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE = "pending_if_incomplete";

    /**
     * AC-31: Stripe の metadata へ焼き付ける operationId のキー。
     *
     * <p>PR6a の回収が痕跡照合に使うキーと<b>同一</b>でなければならない（別キーにすると回収が
     * 停止窓 (a)/(b) を区別できなくなる）。</p>
     */
    String METADATA_OPERATION_ID_KEY =
            BillingContractOperationRecoveryService.STRIPE_METADATA_OPERATION_ID_KEY;

    /**
     * AC-1/AC-2: プラン変更の事前見積りを Stripe から取得する。
     *
     * <p><b>こちらで日割りを計算しない</b>。返ってきた {@link PlanChangeQuote#amountDueNow()} を
     * そのまま {@code amountDueNow} として応答し、{@code billing_change_previews} の
     * {@code amount_snapshot} / {@code tax_snapshot} / 期間・按分基準日時へ書き写す（AC-4）。</p>
     *
     * @param command 見積り要求
     * @return Stripe が返した見積り
     */
    PlanChangeQuote previewPlanChange(PlanChangePreviewCommand command);

    /**
     * AC-29/AC-30/AC-31: プラン変更を Stripe へ適用する。
     *
     * <p>成功時（支払いが通った場合）Stripe は即時に適用し {@code pending_update} を返さない。
     * 追加認証や支払い失敗のときだけ {@code pending_update} が返る（E6'）。</p>
     *
     * @param command 適用要求
     * @return Stripe の応答（invoice / pending_update）
     */
    PlanChangeApplyResult applyPlanChange(PlanChangeApplyCommand command);

    /**
     * 見積り要求。
     *
     * @param subscriptionRef      対象 Stripe Subscription（{@code sub_xxx}）
     * @param targetStripePriceRef 変更後 band の Stripe Price（{@code price_xxx}）
     * @param quantity             数量（人数band課金。個人契約は 1）
     */
    record PlanChangePreviewCommand(String subscriptionRef, String targetStripePriceRef, Integer quantity) {
    }

    /**
     * Stripe が返した見積り（AC-2 の唯一の金額の出所）。
     *
     * @param currency           通貨
     * @param amountDueNow       今すぐ請求される税込額（最小貨幣単位）
     * @param amountExcludingTax 税抜額
     * @param taxAmount          税額
     * @param taxName            税名（null 可）
     * @param taxRateBasisPoints 税率（basis points・null 可）
     * @param periodStart        見積り対象期間の開始
     * @param periodEnd          見積り対象期間の終了
     * @param prorationAt        按分の基準日時
     */
    record PlanChangeQuote(String currency, long amountDueNow, long amountExcludingTax, long taxAmount,
                           String taxName, Integer taxRateBasisPoints,
                           Instant periodStart, Instant periodEnd, Instant prorationAt) {
    }

    /**
     * 適用要求。
     *
     * @param subscriptionRef       対象 Stripe Subscription
     * @param targetStripePriceRef  変更後 band の Stripe Price
     * @param quantity              数量
     * @param operationId           PR6a Saga の operationId（metadata と冪等キーの源）
     * @param stripeIdempotencyKey  AC-30: {@code billing-operation-{operationId}}
     *                              （{@link BillingContractOperationSagaService#stripeIdempotencyKeyOf}）
     * @param prorationBehavior     AC-29: {@link #PRORATION_BEHAVIOR_ALWAYS_INVOICE}
     * @param paymentBehavior       AC-29: {@link #PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE}
     * @param metadata              AC-31: {@link #METADATA_OPERATION_ID_KEY} を含む差分マージ用 metadata
     */
    record PlanChangeApplyCommand(String subscriptionRef, String targetStripePriceRef, Integer quantity,
                                  UUID operationId, String stripeIdempotencyKey,
                                  String prorationBehavior, String paymentBehavior,
                                  Map<String, String> metadata) {
    }

    /**
     * Stripe の応答。
     *
     * @param invoiceRef                  差額請求の Invoice（{@code in_xxx}・0円で発行されない場合は null）
     * @param invoiceStatus               Invoice のステータス（{@code paid} / {@code open} 等・null 可）
     * @param pendingUpdatePresent        AC-33: {@code pending_update} が返ったか（＝追加認証・支払い未了）
     * @param pendingUpdateExpiresAt      {@code pending_update.expires_at}（無ければ null）
     * @param pendingUpdateTargetSnapshot {@code pending_update} の適用内容スナップショット（JSON・無ければ null）
     * @param effectiveAt                 効力発生の瞬間
     */
    record PlanChangeApplyResult(String invoiceRef, String invoiceStatus, boolean pendingUpdatePresent,
                                 Instant pendingUpdateExpiresAt, String pendingUpdateTargetSnapshot,
                                 Instant effectiveAt) {
    }
}
