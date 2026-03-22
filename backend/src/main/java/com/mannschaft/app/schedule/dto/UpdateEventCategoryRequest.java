package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 行事カテゴリ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateEventCategoryRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 7)
    private final String color;

    @Size(max = 50)
    private final String icon;

    private final Boolean isDayOffCategory;

    private final Integer sortOrder;
}
