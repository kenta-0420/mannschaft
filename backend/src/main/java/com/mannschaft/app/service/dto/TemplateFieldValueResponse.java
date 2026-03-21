package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * テンプレートフィールド値レスポンス。
 */
@Getter
@Builder
public class TemplateFieldValueResponse {

    private Long fieldId;
    private String fieldName;
    private String defaultValue;
}
