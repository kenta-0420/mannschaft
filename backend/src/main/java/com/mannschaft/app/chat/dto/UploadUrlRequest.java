package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-signed URL リクエスト DTO。
 *
 * <p>F13 Phase 4-β: スコープ判定（TEAM/ORG/PERSONAL）のために {@code channelId} を必須化した。
 * 統合ストレージクォータの計上先となるチャンネル種別を `channelId` から解決する。</p>
 */
@Getter
@RequiredArgsConstructor
public class UploadUrlRequest {

    /** F13 Phase 4-β: スコープ判定に必要なチャンネル ID。 */
    @NotNull
    private final Long channelId;

    @NotBlank
    @Size(max = 255)
    private final String fileName;

    @NotBlank
    @Size(max = 100)
    private final String contentType;

    @NotNull
    private final Long fileSize;
}
