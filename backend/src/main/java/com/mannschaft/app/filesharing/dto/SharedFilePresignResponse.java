package com.mannschaft.app.filesharing.dto;

/**
 * F13 Phase 5-a: ファイル共有 presign-upload レスポンス DTO。
 *
 * <p>クライアントは {@code uploadUrl} を使って R2 に直接 PUT し、
 * 完了後に {@code fileKey} を {@code createFile} API に渡す。</p>
 *
 * @param uploadUrl       R2 Presigned PUT URL
 * @param fileKey         R2 オブジェクトキー（新統一パス命名規則: files/{scopeType}/{scopeId}/{uuid}.{ext}）
 * @param expiresInSeconds URL 有効期限（秒）
 */
public record SharedFilePresignResponse(
        String uploadUrl,
        String fileKey,
        Long expiresInSeconds
) {}
