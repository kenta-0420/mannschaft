package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指標定義レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MetricResponse {

    private final Long id;
    private final String name;
    private final String unit;
    private final String dataType;
    private final String aggregationType;
    private final String description;
    private final String groupName;
    private final BigDecimal targetValue;
    private final BigDecimal minValue;
    private final BigDecimal maxValue;
    private final Integer sortOrder;
    private final Boolean isVisibleToMembers;
    private final Boolean isSelfRecordable;
    private final Long linkedActivityFieldId;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
