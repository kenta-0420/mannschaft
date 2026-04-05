package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォルダ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFolderRequest {

    @Size(max = 255)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final Long parentId;
}
