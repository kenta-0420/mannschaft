package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タグ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTagRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @Size(max = 7)
    private final String color;

    private final Integer sortOrder;
}
