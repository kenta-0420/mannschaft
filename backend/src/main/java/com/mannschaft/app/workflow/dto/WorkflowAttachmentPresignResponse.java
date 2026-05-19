package com.mannschaft.app.workflow.dto;

/**
 * ワークフロー申請添付ファイルのアップロード用 Pre-signed URL 発行レスポンス DTO。
 *
 * <p>クライアントは {@code uploadUrl} に対して PUT リクエストで直接ファイルをアップロードし、
 * 完了後に {@code fileKey} を {@code POST /attachments} API に渡して登録する。</p>
 *
 * @param uploadUrl        Pre-signed PUT URL（R2 / S3 互換）
 * @param fileKey          R2 オブジェクトキー
 * @param expiresInSeconds URL 有効期限（秒）
 */
public record WorkflowAttachmentPresignResponse(
        String uploadUrl,
        String fileKey,
        long expiresInSeconds
) {
}
