package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォームフィールド定義レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FormFieldResponse {

    private final Long id;
    private final Long templateId;
    private final String fieldKey;
    private final String fieldLabel;
    private final String fieldType;
    private final Boolean isRequired;
    private final Integer sortOrder;
    private final String autoFillKey;
    private final String optionsJson;
    private final String placeholder;
}
