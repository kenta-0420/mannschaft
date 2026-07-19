package com.mannschaft.app.village.dto;

/**
 * 村紋（monsho）アップロード用 presigned PUT URL 発行レスポンス DTO（F17 Phase 2 U7 / #2355）。
 *
 * <p>{@code uploadUrl} は R2 への直接 PUT に使う署名付き URL。{@code r2Key} は将来
 * {@code PUT /monsho} に渡す確定用キー（{@code village/{villageId}/monsho/{uuid}.{ext}} 形式）。
 * 読取は別経路（FE の {@code buildR2Url} による公開 URL 化）で行うため、本レスポンスの
 * {@code r2Key} は生キーであり署名化しない。</p>
 *
 * @param uploadUrl        presigned PUT URL（このURLに画像実体を PUT する）
 * @param r2Key            R2 オブジェクトキー（アップロード完了後 PUT /monsho に渡す）
 * @param expiresInSeconds URL の有効期限（秒）
 */
public record MonshoUploadUrlResponse(
        String uploadUrl,
        String r2Key,
        long expiresInSeconds
) {
}
