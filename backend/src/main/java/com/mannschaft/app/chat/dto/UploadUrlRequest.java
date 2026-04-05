package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-signed URLリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UploadUrlRequest {

    @NotBlank
    @Size(max = 255)
    private final String fileName;

    @NotBlank
    @Size(max = 100)
    private final String contentType;

    @NotNull
    private final Long fileSize;
}
