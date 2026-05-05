package com.mannschaft.app.circulation.dto;

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload レスポンス DTO。
 *
 * <p>クライアントは {@code uploadUrl} を使って R2 に直接 PUT し、
 * 完了後に {@code fileKey} を {@code addAttachment} API に渡す。</p>
 *
 * @param uploadUrl       R2 Presigned PUT URL
 * @param fileKey         R2 オブジェクトキー（新統一パス命名規則: circulation/{scopeType}/{scopeId}/{documentId}/{uuid}）
 * @param expiresInSeconds URL 有効期限（秒）
 */
public record CirculationAttachmentPresignResponse(
        String uploadUrl,
        String fileKey,
        Long expiresInSeconds
) {}
