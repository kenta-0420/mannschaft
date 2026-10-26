package com.mannschaft.app.performance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 指標定義作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateMetricRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 30)
    private final String unit;

    @Size(max = 10)
    private final String dataType;

    @Size(max = 10)
    private final String aggregationType;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String groupName;

    private final BigDecimal targetValue;

    private final BigDecimal minValue;

    private final BigDecimal maxValue;

    private final Integer sortOrder;

    private final Boolean isVisibleToMembers;

    private final Boolean isSelfRecordable;

    private final Long linkedActivityFieldId;
}
