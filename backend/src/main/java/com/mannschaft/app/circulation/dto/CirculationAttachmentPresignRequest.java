package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload リクエスト DTO。
 *
 * <p>クライアントがアップロード前に呼び出し、サーバー側で R2 パスを生成してもらう。</p>
 */
public record CirculationAttachmentPresignRequest(
        @NotNull
        @Size(max = 255)
        String fileName,

        @NotNull
        @Size(max = 100)
        String contentType,

        @NotNull
        @Positive
        Long fileSize
) {}
