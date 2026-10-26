package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ワークフロー申請添付ファイル登録リクエスト DTO。
 *
 * <p>Pre-signed URL でアップロード完了した後、本 API で添付ファイルメタデータを DB に登録する。</p>
 *
 * @param fileKey          R2 オブジェクトキー（{@code presign-upload} レスポンスで返却された値）
 * @param originalFilename アップロード時の元ファイル名
 * @param fileSize         ファイルサイズ（バイト。1〜20 MB）
 */
public record WorkflowAttachmentRegisterRequest(
        @NotBlank @Size(max = 500) String fileKey,
        @NotBlank @Size(max = 255) String originalFilename,
        @NotNull @Positive @Max(20L * 1024 * 1024) Long fileSize
) {
}
