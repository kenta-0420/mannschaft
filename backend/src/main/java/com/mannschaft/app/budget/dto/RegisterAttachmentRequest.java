package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 添付ファイル登録リクエスト。アップロード完了後にメタデータを登録する。
 */
public record RegisterAttachmentRequest(

        @NotNull
        Long transactionId,

        @NotBlank
        @Size(max = 255)
        String fileName,

        @NotBlank
        String fileType,

        @NotNull
        Long fileSize,

        @NotBlank
        String s3Key
) {
}
