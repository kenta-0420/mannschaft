package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 予算カテゴリ作成リクエスト。
 */
public record CreateCategoryRequest(

        @NotNull
        Long fiscalYearId,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        String categoryType,

        Long parentId,

        Integer sortOrder,

        @Size(max = 500)
        String description
) {
}
