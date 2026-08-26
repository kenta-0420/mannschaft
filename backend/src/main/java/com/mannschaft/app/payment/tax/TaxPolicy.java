package com.mannschaft.app.payment.tax;

import java.math.BigDecimal;

/**
 * 税計算ポリシー。将来の国別実装（JapanConsumptionTaxPolicy 等）への差し込み口。
 * 現在は NoOpTaxPolicy（税額0）のみ実装。税理士による政策確定後に各国実装を追加予定。
 */
public interface TaxPolicy {

    /**
     * 総額に対する税額を計算する。
     *
     * @param grossAmount 総額
     * @param taxCategory 税区分（例: STANDARD_10 / REDUCED_8 / EXEMPT）
     * @return 税額（0以上）
     */
    BigDecimal calculateTaxAmount(BigDecimal grossAmount, String taxCategory);

    /**
     * 税区分に対応する税率を返す。
     *
     * @param taxCategory 税区分
     * @return 税率（0.1000=10%）
     */
    BigDecimal getTaxRate(String taxCategory);

    /**
     * 指定の税区分が適用可能かを返す。
     *
     * @param taxCategory 税区分
     * @return 適用可能な場合 true
     */
    boolean isApplicable(String taxCategory);
}
