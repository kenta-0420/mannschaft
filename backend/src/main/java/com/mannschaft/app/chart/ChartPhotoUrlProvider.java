package com.mannschaft.app.chart;

import java.time.LocalDateTime;

/**
 * カルテ写真の R2 / Workers URL 生成の抽象化インターフェース。
 * 本番環境では R2 / Cloudflare Workers URL を生成し、開発環境ではプレースホルダー URL を返す。
 */
public interface ChartPhotoUrlProvider {

    /**
     * R2 キーからアクセス URL を生成する。
     * Workers ドメインが設定されている場合は Workers 経由 URL を、
     * 未設定の場合は R2 Pre-signed URL を返す。
     *
     * @param r2Key R2 オブジェクトキー
     * @return アクセス URL
     */
    String generateSignedUrl(String r2Key);

    /**
     * 署名付きURLの有効期限を取得する。
     *
     * @return 有効期限（15分後）
     */
    LocalDateTime getExpiresAt();
}
