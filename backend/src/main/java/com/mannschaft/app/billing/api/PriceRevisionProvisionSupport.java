package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingStripeProductEntity;
import com.mannschaft.app.billing.BillingStripeProductRepository;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.tax.BillingTaxMasterSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 試練隊（第2陣）E群・F群: {@code PriceRevisionProvisionService} / {@code PriceRevisionRetryProvisionService}
 * が共有する Stripe Product 解決・Price 作成のロジック（決定9改訂・決定10）。
 *
 * <p>両サービスが全く同じ「DB先読み→ミス時のみ決定的IDでStripe解決→metadata照合で回収」の手順を
 * 踏むため、fail-forward ループの中核だけをここへ集約する（重複バグの防止）。</p>
 */
final class PriceRevisionProvisionSupport {

    private static final int MAX_PROVISION_ERROR_CODE_LENGTH = 64;

    private PriceRevisionProvisionSupport() {
    }

    /**
     * 1 band分の provision/retry を試みる（fail-forward: 例外を外へ投げない）。
     *
     * <p>手順（決定3改訂・決定9改訂）:
     * <ol>
     *   <li>{@code gateway.findPriceByMetadata} で既存 Price を照合（既にStripe側にあれば再作成せず回収）</li>
     *   <li>ヒットしなければ Product を解決（DB先読み→ミス時のみ決定的IDでStripe解決）</li>
     *   <li>Price を作成する</li>
     * </ol>
     * 結果は {@link PriceRevisionProvisionStateWriter.BandOutcome} で返し、DB への反映（READY/PROVISION_FAILED・
     * provisionAttempts+1）は {@link PriceRevisionProvisionStateWriter#complete} が別トランザクションで行う。
     * 本メソッドはトランザクション外で呼ばれる（AC-69: Stripe 呼び出しを DB トランザクションに含めない）。</p>
     *
     * <p>Stripe Product の {@code tax_code}・Product 解決キーには band snapshot の <b>Stripe 側税コード</b>
     * （{@link BillingTaxMasterSnapshot#stripeTaxCodeOf}）を使う。内部税コード（{@code taxCodeSnapshot}。
     * {@code JP_STANDARD_10} 等）は Stripe へ渡さない（決定7・AC-84）。</p>
     */
    static PriceRevisionProvisionStateWriter.BandOutcome attemptProvisionBand(
            BillingStripeProductRepository stripeProductRepository,
            BillingPriceProvisionGateway gateway,
            UUID revisionId, BillingProductKind productKind, String productKey,
            PriceRevisionProvisionStateWriter.BandTarget band, String environmentId) {
        try {
            Optional<BillingPriceProvisionGateway.PriceSnapshot> recovered =
                    gateway.findPriceByMetadata(revisionId, band.bandId());
            if (recovered.isPresent()) {
                return new PriceRevisionProvisionStateWriter.BandOutcome(
                        band.bandId(), recovered.get().stripePriceId(), null);
            }

            String stripeTaxCode = BillingTaxMasterSnapshot.stripeTaxCodeOf(band.taxMasterSnapshot());
            String stripeProductId = resolveProductId(
                    stripeProductRepository, gateway, productKind, productKey, stripeTaxCode);

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("revisionId", revisionId.toString());
            metadata.put("bandId", band.bandId().toString());
            // AC-79: Stripe の test/live Price 分離。誤って test 環境の Price を live 環境が
            // reconcile で回収してしまう事故を防ぐため、作成時の環境識別子を Price 自身の
            // metadata に焼く（読み戻しは BillingPriceProvisionRecoveryService#reconcileBand）。
            metadata.put("environmentId", environmentId);
            BillingPriceProvisionGateway.PriceCreationCommand command =
                    new BillingPriceProvisionGateway.PriceCreationCommand(
                            revisionId, band.bandId(), stripeProductId, band.inputAmount(), "jpy", "month", 1,
                            band.taxBehavior().name(), metadata, "price-band-create:" + band.bandId());
            BillingPriceProvisionGateway.PriceCreationResult result = gateway.createPrice(command);

            return new PriceRevisionProvisionStateWriter.BandOutcome(band.bandId(), result.stripePriceId(), null);
        } catch (RuntimeException e) {
            return new PriceRevisionProvisionStateWriter.BandOutcome(band.bandId(), null, truncate(
                    e.getClass().getSimpleName() + ":" + e.getMessage(), MAX_PROVISION_ERROR_CODE_LENGTH));
        }
    }

    /**
     * 決定9改訂: {@code billing_stripe_products} をDB先読みし、ヒットしなければ決定的Product IDで解決する。
     * 新規解決した場合はマッピングをDBへ永続化する（AC-88a/AC-88c）。
     *
     * @param stripeTaxCode Stripe 側税コード（{@code txcd_...}。未設定なら null）。内部税コードではない
     */
    static String resolveProductId(
            BillingStripeProductRepository stripeProductRepository, BillingPriceProvisionGateway gateway,
            BillingProductKind productKind, String productKey, String stripeTaxCode) {
        Optional<BillingStripeProductEntity> existing = stripeProductRepository
                .findByProductKindAndProductKeyAndStripeTaxCode(productKind, productKey, stripeTaxCode);
        if (existing.isPresent()) {
            return existing.get().getStripeProductId();
        }

        String deterministicProductId = deterministicProductId(productKind, productKey, stripeTaxCode);
        BillingPriceProvisionGateway.ProductResolution resolution = gateway.resolveOrCreateProduct(
                new BillingPriceProvisionGateway.ProductResolutionCommand(
                        productKind, productKey, stripeTaxCode, deterministicProductId,
                        "price-product-create:" + deterministicProductId));

        stripeProductRepository.save(new BillingStripeProductEntity(
                productKind, productKey, stripeTaxCode, resolution.stripeProductId()));
        return resolution.stripeProductId();
    }

    /** 決定10: {@code productKind:productKey:stripeTaxCodeOrEmpty} から導出する固定長ハッシュ文字列。 */
    static String deterministicProductId(BillingProductKind productKind, String productKey, String taxCode) {
        String seed = productKind.name() + ":" + productKey + ":" + (taxCode == null ? "" : taxCode);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "prod_rev_" + hex.substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            // JVM 標準アルゴリズムであり実運用で発生しない。握り潰さず致命的として扱う。
            throw new IllegalStateException("SHA-256 が利用できない環境です", e);
        }
    }

    static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    static PriceRevisionBandResponse toBandResponse(BillingPriceBandVersionEntity band) {
        return PriceRevisionBandResponse.builder()
                .id(band.getId())
                .bandNo(band.getBandNo())
                .minMembers(band.getMinMembers())
                .maxMembers(band.getMaxMembers())
                .inputAmount(band.getInputAmount())
                .taxBehavior(band.getTaxBehavior())
                .taxCode(band.getTaxCodeSnapshot())
                .amountExcludingTax(band.getAmountExcludingTax())
                .taxAmount(band.getTaxAmount())
                .amountIncludingTax(band.getAmountIncludingTax())
                .taxRateBasisPoints(band.getTaxRateBasisPoints())
                .status(band.getStatus())
                .stripePriceRef(band.getStripePriceRef())
                .provisionErrorCode(band.getProvisionErrorCode())
                .provisionAttempts(nz(band.getProvisionAttempts()))
                .build();
    }
}
