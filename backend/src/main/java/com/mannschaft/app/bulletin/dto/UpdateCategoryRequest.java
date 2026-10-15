package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カテゴリ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCategoryRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @Size(max = 200)
    private final String description;

    private final Integer displayOrder;

    @Size(max = 7)
    private final String color;

    @Size(max = 20)
    private final String postMinRole;
}
