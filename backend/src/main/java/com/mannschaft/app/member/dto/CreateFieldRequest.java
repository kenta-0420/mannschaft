package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フィールド定義作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFieldRequest {

    private final Long teamId;

    private final Long organizationId;

    @NotBlank
    @Size(max = 100)
    private final String fieldName;

    private final String fieldType;

    private final String options;

    private final Boolean isRequired;

    private final Integer sortOrder;
}
