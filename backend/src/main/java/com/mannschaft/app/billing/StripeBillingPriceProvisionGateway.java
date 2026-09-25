package com.mannschaft.app.billing;

import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 価格改定（price-revisions）E群/F群: {@link BillingPriceProvisionGateway} の Stripe 実装（出陣隊第4陣）。
 *
 * <p>{@link StripeBillingPlanChangeGateway} と同じ流儀で、Stripe SDK 依存は payment ドメインの
 * {@link StripePaymentProvider} に封じ込める（本クラスは {@code com.stripe} を一切 import しない）。
 * 本クラスが担うのは「{@code BillingPriceProvisionGateway} の語彙（billing 側の record）」と
 * 「{@code StripePaymentProvider} の語彙（Product/Price 解決の実手順）」の間の写像だけであり、
 * 金額の計算・状態の判断は一切行わない。</p>
 *
 * <h2>なぜ本クラスが必要だったか</h2>
 * <p>本クラスは本コミットまで {@code TEMP_STUB}（3メソッドすべてが
 * {@link UnsupportedOperationException} を投げるだけ）であり、Stripe への Product/Price 作成が
 * 一歩も動かなかった。この戦役（価格改定 API）はマスターの「価格をそもそも登録する手段が無いのを
 * 直せ」という御下命から始まっており、provision の実経路が stub のままでは CI が緑でも成果は
 * ゼロである（「ポートだけ在って実装が無くても全緑」の再演を避ける）。
 * 一方で {@link StripePaymentProvider}／{@code StripePaymentProviderImpl} 側には
 * {@code resolveOrCreateProduct}/{@code createPriceForRevision}/
 * {@code findPriceByRevisionAndBandMetadata} が既に実装済みだった（決定9・決定10・第5版の
 * reconcile 全属性照合対応）ため、本クラスはその実装へ配線する薄いアダプタとして書く。</p>
 *
 * <h2>metadata の取り決め</h2>
 * <p>Stripe Product には {@code productKind}/{@code productKey} を metadata として焼き、
 * {@link #findPriceByMetadata} 側で {@link StripePaymentProvider.PriceMetadataSnapshot#productMetadata()}
 * から同じキーで読み戻す（このキー名は本クラス内で完結する取り決めであり、他クラスへの依存はない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingPriceProvisionGateway implements BillingPriceProvisionGateway {

    private static final String METADATA_PRODUCT_KIND = "productKind";
    private static final String METADATA_PRODUCT_KEY = "productKey";
    private static final String METADATA_ENVIRONMENT_ID = "environmentId";

    private final StripePaymentProvider stripePaymentProvider;

    /**
     * 決定9改訂・AC-74/75/83/84: 決定的 Product ID で Product を解決または新規作成する。
     *
     * <p>同時作成競合（{@code resource_already_exists}）時の retrieve 収束は
     * {@code StripePaymentProviderImpl#resolveOrCreateProduct} 側の責務（本クラスは結果をそのまま
     * 写すだけ）。Product 表示名は Stripe ダッシュボードで判別できるよう
     * {@code productKind:productKey} を使う（業務的な意味は持たない）。</p>
     */
    @Override
    public ProductResolution resolveOrCreateProduct(ProductResolutionCommand command) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_PRODUCT_KIND, command.productKind().name());
        metadata.put(METADATA_PRODUCT_KEY, command.productKey());
        String productName = command.productKind() + ":" + command.productKey();

        StripePaymentProvider.ProductResolutionInfo resolved = stripePaymentProvider.resolveOrCreateProduct(
                command.deterministicProductId(), productName, metadata, command.stripeTaxCode(),
                command.idempotencyKey());

        log.info("価格改定: Stripe Product 解決 productId={} newlyCreated={}",
                resolved.stripeProductId(), resolved.newlyCreated());
        return new ProductResolution(resolved.stripeProductId(), resolved.newlyCreated());
    }

    /**
     * AC-79〜82/86: band snapshot から Stripe Price を作成する。
     *
     * <p>金額・税表示方式・冪等キーは呼び出し側（{@code PriceRevisionProvisionSupport}）が
     * band から組み立てたものをそのまま渡す。本クラスでの再計算・丸め直しは行わない。</p>
     */
    @Override
    public PriceCreationResult createPrice(PriceCreationCommand command) {
        String stripePriceId = stripePaymentProvider.createPriceForRevision(
                command.stripeProductId(), command.unitAmount(), command.currency(),
                command.recurringInterval(), command.recurringIntervalCount(), command.taxBehavior(),
                command.metadata(), command.idempotencyKey());

        log.info("価格改定: Stripe Price 作成 priceId={} productId={} revisionId={} bandId={}",
                stripePriceId, command.stripeProductId(), command.revisionId(), command.bandId());
        return new PriceCreationResult(stripePriceId);
    }

    /**
     * AC-91/96: metadata（revisionId/bandId）で既存 Stripe Price を照合する
     * （fail-forward の再実行・retry-provision・reconcile-provision の全属性照合が使う）。
     *
     * <p>本メソッドが返す {@code productKind}/{@code productKey} は Stripe Product 側の
     * metadata から読み戻す（{@link #resolveOrCreateProduct} が書いたのと同じキー）。
     * 該当キーが無い場合（本クラス以外の経路で作られた Product 等）は null を返し、
     * 呼び出し側（{@code BillingPriceProvisionRecoveryService}）の全属性照合で
     * 不一致として扱わせる（symptom を隠さない）。</p>
     */
    @Override
    public Optional<PriceSnapshot> findPriceByMetadata(UUID revisionId, UUID bandId) {
        return stripePaymentProvider.findPriceByRevisionAndBandMetadata(
                        revisionId.toString(), bandId.toString())
                .map(snapshot -> new PriceSnapshot(
                        snapshot.stripePriceId(),
                        snapshot.unitAmount(),
                        snapshot.currency(),
                        snapshot.recurringInterval(),
                        snapshot.recurringIntervalCount(),
                        snapshot.productMetadata().get(METADATA_PRODUCT_KIND),
                        snapshot.productMetadata().get(METADATA_PRODUCT_KEY),
                        snapshot.productTaxCode(),
                        snapshot.taxBehavior(),
                        snapshot.priceMetadata().get(METADATA_ENVIRONMENT_ID)));
    }
}
