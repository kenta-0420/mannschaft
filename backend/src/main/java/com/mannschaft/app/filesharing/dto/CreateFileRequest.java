package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ファイル作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFileRequest {

    @NotNull
    private final Long folderId;

    @NotBlank
    @Size(max = 255)
    private final String name;

    @NotBlank
    @Size(max = 500)
    private final String fileKey;

    @NotNull
    private final Long fileSize;

    @NotBlank
    @Size(max = 100)
    private final String contentType;

    @Size(max = 500)
    private final String description;
}
