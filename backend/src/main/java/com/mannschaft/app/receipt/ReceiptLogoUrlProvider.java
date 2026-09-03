package com.mannschaft.app.receipt;

/**
 * 領収書ロゴの署名付きアクセス URL 生成の抽象化（F08.4 §9.1.1 D-8）。
 *
 * <p>金型は {@code chart} ドメインの {@code ChartPhotoUrlProvider} だが、同インターフェースが
 * 持つ {@code getExpiresAt()}（TTL 15 分固定）は実際の署名寿命
 * （{@code mannschaft.storage.presigned-download-ttl}・既定 3600 秒）と一致していない。
 * その不整合を持ち込まないため、本インターフェースは有効期限を露出させず
 * URL 生成のみを責務とする。</p>
 */
public interface ReceiptLogoUrlProvider {

    /**
     * ストレージキーから署名付き GET URL を生成する。
     *
     * @param storageKey ストレージキー（{@code null} 可）
     * @return 署名付き URL。{@code storageKey} が null / 空なら {@code null}
     */
    String generateLogoUrl(String storageKey);
}
