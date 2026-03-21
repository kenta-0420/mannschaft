package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォルダ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFolderRequest {

    @NotBlank
    @Size(max = 255)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final Long parentId;

    @NotNull
    private final String scopeType;
}
