package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートフィールドレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateFieldResponse {

    private final Long id;
    private final Long templateId;
    private final String fieldKey;
    private final String fieldLabel;
    private final String fieldType;
    private final Boolean isRequired;
    private final Integer sortOrder;
    private final String optionsJson;
}
