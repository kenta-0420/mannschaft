package com.mannschaft.app.chart.dto;

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

    @NotBlank
    @Size(max = 100)
    private final String fieldName;

    @NotBlank
    @Size(max = 20)
    private final String fieldType;

    private final String options;

    private final Integer sortOrder;
}
