package com.mannschaft.app.billing;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 価格改定戦役（price-revisions）E群・F群: 同期 Provision の Stripe 窓口（<b>試練隊（第2陣）が置いた発注書</b>）。
 *
 * <p>{@link BillingPlanChangeGateway} と同じ流儀（実装は Stripe SDK を直接触らず
 * {@code StripePaymentProvider} 経由に置く）。試練は実装より先に書かれるため、テストが Stripe の
 * 戻り値を差し替えられなければ fail-forward（AC-72）も Product 解決キーの分離（AC-74/75）も
 * 観測できない。</p>
 *
 * <h2>決定9改訂: Product 解決アルゴリズム</h2>
 * <ol>
 *   <li>{@code billing_stripe_products} を {@code (productKind, productKey, stripeTaxCode)} でDB検索
 *       （{@link BillingStripeProductRepository}）。ヒットすれば本ポートを一切呼ばない</li>
 *   <li>ミス時のみ {@link #resolveOrCreateProduct} を呼ぶ。決定的 Stripe Product ID で create を1回。
 *       同時作成競合時のみ {@code newlyCreated=false} を返す（呼び出し側は retrieve 経由の収束とみなし、
 *       {@code billing_stripe_products} への保存は一意制約 CAS に委ねる）</li>
 * </ol>
 *
 * <p><b>出陣隊への申し送り</b>: このポート名・引数を変えるなら、
 * {@code PriceRevisionProvisionServiceTest} / {@code PriceRevisionRetryReconcileServiceTest} の
 * {@code @Mock} も同時に移すこと。</p>
 */
public interface BillingPriceProvisionGateway {

    /**
     * AC-74/75/83/84: {@code productKind+productKey+stripeTaxCode} で Product を解決または新規作成する。
     * 既存 Product（DB マッピングにヒットした場合）は本メソッドを呼ばない（呼び出し側の責務）。
     */
    ProductResolution resolveOrCreateProduct(ProductResolutionCommand command);

    /** AC-79〜82/86: band snapshot から Stripe Price を作成する。 */
    PriceCreationResult createPrice(PriceCreationCommand command);

    /**
     * AC-91/96: metadata（revisionId/bandId）で既存 Stripe Price を照合する。
     * 見つかった場合は reconcile/retry が全属性再照合（決定3改訂）に使う snapshot を返す。
     */
    Optional<PriceSnapshot> findPriceByMetadata(UUID revisionId, UUID bandId);

    /**
     * Product 解決要求。
     *
     * @param productKind         PLAN/ADDON
     * @param productKey          商品キー
     * @param stripeTaxCode       Stripe 側税コード（null 許容。決定9改訂の解決キーの一部）
     * @param deterministicProductId 決定的 Stripe Product ID（決定10）
     * @param idempotencyKey      {@code price-product-create:{deterministicProductId}}（決定10）
     */
    record ProductResolutionCommand(
            BillingProductKind productKind, String productKey, String stripeTaxCode,
            String deterministicProductId, String idempotencyKey) {
    }

    /**
     * Product 解決結果。
     *
     * @param stripeProductId 解決された Stripe Product ID
     * @param newlyCreated    true: この呼び出しで新規作成された。false: 同時作成競合により
     *                        既存 Product の retrieve に収束した（AC-88c）
     */
    record ProductResolution(String stripeProductId, boolean newlyCreated) {
    }

    /**
     * Price 作成要求。
     *
     * @param revisionId            親 revision（metadata へ焼き付ける）
     * @param bandId                対象 band（metadata へ焼き付ける・Idempotency-Key の源）
     * @param stripeProductId       紐づける Stripe Product
     * @param unitAmount            band の {@code inputAmount}
     * @param currency              {@code "jpy"} 固定
     * @param recurringInterval     {@code "month"} 固定
     * @param recurringIntervalCount 1 固定
     * @param taxBehavior           band の {@code taxBehavior}（{@code INCLUSIVE}/{@code EXCLUSIVE}）
     * @param metadata              {@code revisionId}/{@code bandId}/環境識別子を含む
     * @param idempotencyKey        {@code price-band-create:{bandId}}（決定10）
     */
    record PriceCreationCommand(
            UUID revisionId, UUID bandId, String stripeProductId, long unitAmount, String currency,
            String recurringInterval, int recurringIntervalCount, String taxBehavior,
            Map<String, String> metadata, String idempotencyKey) {
    }

    /** Price 作成結果。 */
    record PriceCreationResult(String stripePriceId) {
    }

    /**
     * reconcile/retry の全属性再照合（決定3改訂）に使う Stripe 側 snapshot。
     *
     * @param stripePriceId  Stripe Price ID
     * @param unitAmount     Stripe 側 unit_amount
     * @param currency       Stripe 側 currency
     * @param recurringInterval Stripe 側 recurring.interval
     * @param recurringIntervalCount Stripe 側 recurring.interval_count
     * @param productKind    Stripe Product metadata から読める productKind
     * @param productKey     Stripe Product metadata から読める productKey
     * @param productTaxCode Stripe Product 実体の {@code tax_code}（第5版・重大3対応で追加）
     * @param taxBehavior    Stripe 側 tax_behavior
     * @param environmentId  metadata の環境識別子
     */
    record PriceSnapshot(
            String stripePriceId, long unitAmount, String currency, String recurringInterval,
            int recurringIntervalCount, String productKind, String productKey, String productTaxCode,
            String taxBehavior, String environmentId) {
    }
}
