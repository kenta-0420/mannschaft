package com.mannschaft.app.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * ナレッジベース画像アップロードURL取得リクエスト。
 */
public record KbUploadUrlRequest(
        @NotBlank String contentType,
        @Positive long fileSize
) {}
