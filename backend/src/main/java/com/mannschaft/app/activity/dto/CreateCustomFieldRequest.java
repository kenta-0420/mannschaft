package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カスタムフィールド作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCustomFieldRequest {

    private final Long teamId;
    private final Long organizationId;
    private final String scope;

    @NotBlank
    @Size(max = 100)
    private final String fieldName;

    @NotBlank
    private final String fieldType;

    private final String options;

    @Size(max = 20)
    private final String unit;

    private final Boolean isRequired;
    private final Integer sortOrder;
}
