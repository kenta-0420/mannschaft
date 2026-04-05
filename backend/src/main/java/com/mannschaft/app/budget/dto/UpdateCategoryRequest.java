package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 予算カテゴリ更新リクエスト。
 */
public record UpdateCategoryRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        Integer sortOrder,

        @Size(max = 500)
        String description
) {
}
