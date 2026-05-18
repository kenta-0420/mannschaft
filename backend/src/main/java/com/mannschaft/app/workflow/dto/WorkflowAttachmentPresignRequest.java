package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * ワークフロー申請添付ファイルのアップロード用 Pre-signed URL 発行リクエスト DTO。
 *
 * <p>F05.6 Phase 11 第二陣（2-γ）で追加。クライアントが添付ファイルをアップロードする前に、
 * 本 API でアップロード先 URL を取得し、PUT で直接 R2 (S3 互換) にアップロードする。</p>
 *
 * @param contentType MIME タイプ（許可リスト: PDF, JPEG, PNG, WebP, GIF, XLSX, DOCX, CSV）
 * @param fileSize ファイルサイズ（バイト。1〜20 MB）
 */
public record WorkflowAttachmentPresignRequest(
        @NotBlank String contentType,
        @NotNull @Positive @Max(20L * 1024 * 1024) Long fileSize
) {
}
