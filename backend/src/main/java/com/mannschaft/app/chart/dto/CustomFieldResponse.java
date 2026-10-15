package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カスタムフィールドレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CustomFieldResponse {

    private final Long id;
    private final String fieldName;
    private final String fieldType;
    private final String options;
    private final Integer sortOrder;
    private final Boolean isActive;
}
