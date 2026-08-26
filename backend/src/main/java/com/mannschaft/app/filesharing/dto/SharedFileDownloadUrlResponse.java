package com.mannschaft.app.filesharing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ファイル共有 download-url レスポンス DTO。
 *
 * <p>{@code GET /api/v1/files/{fileId}/download-url} のレスポンス。
 * クライアントは認可を通過したファイルに対して、R2 の Presigned GET URL を取得し、
 * 直接ブラウザでダウンロード（または新規タブ表示）する。</p>
 *
 * <p>FE 契約: {@code { data: { downloadUrl: string } }}。{@code expiresInSeconds} は補足情報。</p>
 *
 * @param downloadUrl      R2 Presigned GET URL
 * @param expiresInSeconds URL 有効期限（秒）
 */
@Schema(name = "SharedFileDownloadUrlResponse", description = "ファイル共有 ダウンロードURL レスポンス")
public record SharedFileDownloadUrlResponse(
        @Schema(description = "R2 Presigned GET URL", example = "https://r2.example.com/files/TEAM/5/uuid.pdf?X-Amz-...")
        String downloadUrl,
        @Schema(description = "URL 有効期限（秒）", example = "900")
        Long expiresInSeconds
) {}
