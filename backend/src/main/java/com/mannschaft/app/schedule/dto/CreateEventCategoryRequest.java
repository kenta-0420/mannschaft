package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 行事カテゴリ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateEventCategoryRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    @Size(max = 7)
    private final String color;

    @Size(max = 50)
    private final String icon;

    private final Boolean isDayOffCategory;

    private final Integer sortOrder;
}
