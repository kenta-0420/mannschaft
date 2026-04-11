package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 カテゴリマスタの単体レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentCategoryResponse {

    private final Long id;
    private final String code;
    private final String nameI18nKey;
    private final String icon;
    private final String defaultParticipationType;
    private final Integer displayOrder;
    private final Boolean isActive;
}
