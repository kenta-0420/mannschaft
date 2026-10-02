package com.mannschaft.app.billing;

/**
 * 価格カタログが扱う商品の種別。
 *
 * <p>{@code billing_price_versions.product_kind} および
 * {@code billing_price_band_versions.product_kind} の値と一致する。</p>
 */
public enum BillingProductKind {
    PLAN,
    ADDON
}
