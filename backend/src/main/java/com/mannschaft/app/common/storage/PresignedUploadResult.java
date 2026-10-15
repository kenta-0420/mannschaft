package com.mannschaft.app.common.storage;

/**
 * Pre-signed アップロードURL生成結果。
 *
 * @param uploadUrl       署名付きアップロードURL
 * @param s3Key           S3オブジェクトキー
 * @param expiresInSeconds 有効期限（秒）
 */
public record PresignedUploadResult(String uploadUrl, String s3Key, long expiresInSeconds) {
}
