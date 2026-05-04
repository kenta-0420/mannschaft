package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.common.storage.PresignedUploadResult;

/**
 * スキャン文書アップロード用 presigned URL レスポンス（F14.1）。
 *
 * @param uploadUrl       署名付きアップロードURL（PUT）
 * @param s3Key           S3オブジェクトキー（同意書登録時に提出する）
 * @param expiresInSeconds 有効期限（秒）
 */
public record ScanUploadUrlResponse(String uploadUrl, String s3Key, long expiresInSeconds) {

    public static ScanUploadUrlResponse from(PresignedUploadResult result) {
        return new ScanUploadUrlResponse(result.uploadUrl(), result.s3Key(), result.expiresInSeconds());
    }
}
