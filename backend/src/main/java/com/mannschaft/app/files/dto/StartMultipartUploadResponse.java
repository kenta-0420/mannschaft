package com.mannschaft.app.files.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Multipart Upload 開始レスポンス DTO。
 * クライアントはこのレスポンスの uploadId と fileKey を使って
 * パート URL 発行・完了操作を行う。
 */
@Getter
@RequiredArgsConstructor
public class StartMultipartUploadResponse {

    /** R2 Multipart Upload ID */
    private final String uploadId;

    /** R2 オブジェクトキー */
    private final String fileKey;

    /** パート数 */
    private final int partCount;

    /** パートサイズ（バイト） */
    private final long partSize;
}
