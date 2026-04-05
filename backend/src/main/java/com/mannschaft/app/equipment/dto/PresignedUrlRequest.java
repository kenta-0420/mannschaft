package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-signed URL リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresignedUrlRequest {

    @NotBlank
    private final String contentType;

    @NotNull
    private final Long fileSize;
}
