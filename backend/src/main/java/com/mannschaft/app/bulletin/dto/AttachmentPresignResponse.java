package com.mannschaft.app.bulletin.dto;

/**
 * 掲示板添付ファイル presign-upload レスポンス DTO。
 *
 * <p>クライアントは {@code uploadUrl} を使って R2 に直接 PUT し、完了後に {@code fileKey} を
 * 確定 API（{@code POST /api/v1/bulletin/attachments}）に渡してメタデータを登録する。</p>
 *
 * @param uploadUrl        R2 Presigned PUT URL
 * @param fileKey          R2 オブジェクトキー
 *                         （命名規則: {@code bulletin/{scopeType}/{scopeId}/{targetType}/{targetId}/{uuid}}）
 * @param expiresInSeconds URL 有効期限（秒）
 */
public record AttachmentPresignResponse(
        String uploadUrl,
        String fileKey,
        long expiresInSeconds
) {}
