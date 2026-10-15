package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * F13 Phase 5-a: ファイル共有 presign-upload リクエスト DTO。
 *
 * <p>クライアントがアップロード前に呼び出し、サーバー側で R2 パスを生成してもらう。</p>
 */
public record SharedFilePresignRequest(
        @NotNull
        Long folderId,

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
