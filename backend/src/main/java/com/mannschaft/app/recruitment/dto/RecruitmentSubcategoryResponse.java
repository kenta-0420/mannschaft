package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 サブカテゴリの単体レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentSubcategoryResponse {

    private final Long id;
    private final Long categoryId;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final Integer displayOrder;
}
