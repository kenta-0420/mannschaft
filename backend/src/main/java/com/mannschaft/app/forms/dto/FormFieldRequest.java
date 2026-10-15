package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォームフィールド定義リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FormFieldRequest {

    @NotBlank
    @Size(max = 50)
    private final String fieldKey;

    @NotBlank
    @Size(max = 100)
    private final String fieldLabel;

    @NotBlank
    @Size(max = 20)
    private final String fieldType;

    private final Boolean isRequired;

    private final Integer sortOrder;

    @Size(max = 50)
    private final String autoFillKey;

    private final String optionsJson;

    @Size(max = 200)
    private final String placeholder;
}
