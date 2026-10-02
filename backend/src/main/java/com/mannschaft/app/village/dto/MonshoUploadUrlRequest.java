package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 村紋（monsho）アップロード用 presigned PUT URL 発行リクエスト DTO（F17 Phase 2 U7 / #2355）。
 *
 * <p>クライアントは R2 に画像実体をアップロードする前に本 API を呼び、払い出された
 * presigned PUT URL に対して直接 PUT する。アップロード完了後、返却された {@code r2Key} を
 * 既存の {@code PUT /api/v1/villages/{villageId}/monsho} に渡して DB を確定させる。</p>
 *
 * @param contentType アップロードする画像の Content-Type（必須。許可: image/jpeg / image/png / image/webp）
 * @param fileSize    アップロードする画像のバイト数（サーバ側で上限検証する）
 */
public record MonshoUploadUrlRequest(

        @NotBlank
        String contentType,

        long fileSize
) {
}
