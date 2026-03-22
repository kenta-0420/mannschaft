package com.mannschaft.app.resident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 書類アップロードリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UploadDocumentRequest {

    @NotBlank
    @Size(max = 30)
    private final String documentType;

    @NotBlank
    @Size(max = 255)
    private final String fileName;

    @NotBlank
    @Size(max = 500)
    private final String s3Key;

    @NotNull
    private final Integer fileSize;

    @NotBlank
    @Size(max = 100)
    private final String contentType;
}
