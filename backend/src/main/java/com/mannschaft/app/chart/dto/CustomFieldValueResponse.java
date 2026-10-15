package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カスタムフィールド値レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CustomFieldValueResponse {

    private final Long fieldId;
    private final String fieldName;
    private final String value;
}
