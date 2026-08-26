package com.mannschaft.app.bulletin.dto;

/**
 * 掲示板添付ファイル ダウンロード用 presigned GET URL レスポンス DTO。
 *
 * <p>生の {@code fileKey} は返さず、短命 TTL の署名付き GET URL のみを返す
 * （IDOR・キー推測の防止）。</p>
 *
 * @param downloadUrl      短命 TTL の R2 Presigned GET URL
 * @param expiresInSeconds URL 有効期限（秒）
 */
public record AttachmentDownloadUrlResponse(
        String downloadUrl,
        long expiresInSeconds
) {}
