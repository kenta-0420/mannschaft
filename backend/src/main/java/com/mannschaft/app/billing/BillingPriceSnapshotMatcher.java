package com.mannschaft.app.billing;

import com.mannschaft.app.billing.tax.BillingTaxMasterSnapshot;

import java.util.Objects;

/**
 * Stripe 側で見つかった Price（{@link BillingPriceProvisionGateway.PriceSnapshot}）が band の snapshot と
 * 全属性一致するかの判定（決定3改訂・AC-96/AC-97/AC-97a/AC-79）。
 *
 * <p>reconcile-provision と retry-provision / provision の「metadata で既存 Price を見つけたら再作成せず回収する」
 * 経路（AC-91）の両方がこの判定を通す。metadata（revisionId/bandId）の一致だけを根拠に採用すると、
 * 属性が食い違う（汚染された・手動で改変された）Price を販売可能にしてしまうため（2026-09-24 検分指摘で
 * retry 経路が素通りしていたことが判明し、判定を共通化した）。</p>
 */
public final class BillingPriceSnapshotMatcher {

    private BillingPriceSnapshotMatcher() {
    }

    /**
     * @param taxMasterSnapshot band の {@code tax_master_snapshot}（Stripe 側税コードを含む）
     * @param environmentId     現在の実行環境の識別子（test/live/unknown）
     */
    public static boolean matches(BillingPriceProvisionGateway.PriceSnapshot snapshot,
            BillingProductKind productKind, String productKey, long inputAmount, BillingTaxBehavior taxBehavior,
            String taxMasterSnapshot, String environmentId) {
        return snapshot.unitAmount() == inputAmount
                && "jpy".equalsIgnoreCase(snapshot.currency())
                && "month".equalsIgnoreCase(snapshot.recurringInterval())
                && snapshot.recurringIntervalCount() == 1
                && productKind.name().equals(snapshot.productKind())
                && productKey.equals(snapshot.productKey())
                && taxBehavior.name().equalsIgnoreCase(snapshot.taxBehavior())
                // AC-97a: Product 実体の tax_code まで一致しなければ回収しない。比較対象は band snapshot の
                // Stripe 側税コード（txcd_...）であり、内部 code（taxCodeSnapshot）ではない（決定7）。
                && Objects.equals(BillingTaxMasterSnapshot.stripeTaxCodeOf(taxMasterSnapshot),
                        snapshot.productTaxCode())
                // AC-79: test/live Price 分離。"unknown" 同士は素直な等値比較で一致扱いになる。
                && Objects.equals(environmentId, snapshot.environmentId());
    }
}
