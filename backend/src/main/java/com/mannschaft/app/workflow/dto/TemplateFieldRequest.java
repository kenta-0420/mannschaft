package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートフィールド定義リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateFieldRequest {

    @NotBlank
    @Size(max = 50)
    private final String fieldKey;

    @NotBlank
    @Size(max = 100)
    private final String fieldLabel;

    @NotBlank
    @Size(max = 20)
    private final String fieldType;

    @NotNull
    private final Boolean isRequired;

    private final Integer sortOrder;

    private final String optionsJson;
}
