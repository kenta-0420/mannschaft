package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カスタムフィールド値リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CustomFieldValueRequest {

    @NotNull
    private final Long fieldId;

    private final String value;
}
