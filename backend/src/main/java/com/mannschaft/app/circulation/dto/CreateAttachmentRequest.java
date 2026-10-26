package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 添付ファイル作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateAttachmentRequest {

    @NotBlank
    @Size(max = 500)
    private final String fileKey;

    @NotBlank
    @Size(max = 255)
    private final String originalFilename;

    @NotNull
    private final Long fileSize;

    @NotBlank
    @Size(max = 100)
    private final String mimeType;
}
