package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 サブカテゴリ作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateRecruitmentSubcategoryRequest {

    @NotNull
    private final Long categoryId;

    @NotNull
    @Size(max = 100)
    private final String name;

    private final Integer displayOrder;
}
