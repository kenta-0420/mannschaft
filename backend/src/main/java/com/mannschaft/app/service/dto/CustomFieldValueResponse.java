package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * カスタムフィールド値レスポンス。
 */
@Getter
@Builder
public class CustomFieldValueResponse {

    private Long fieldId;
    private String fieldName;
    private String fieldType;
    private String value;
}
