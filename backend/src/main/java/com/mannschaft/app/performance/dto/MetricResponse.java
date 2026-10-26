package com.mannschaft.app.performance.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指標定義レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class MetricResponse {

    private final Long id;
    private final MetricDefinitionDto definition;
    private final MetricValueRangeDto valueRange;
    private final MetricAccessDto access;
    private final Integer sortOrder;
    private final MetricAuditDto audit;

    public record MetricDefinitionDto(
            String name,
            String unit,
            String dataType,
            String aggregationType,
            String description,
            String groupName) {}

    public record MetricValueRangeDto(
            BigDecimal targetValue,
            BigDecimal minValue,
            BigDecimal maxValue) {}

    public record MetricAccessDto(
            Boolean isVisibleToMembers,
            Boolean isSelfRecordable,
            Long linkedActivityFieldId,
            Boolean isActive) {}

    public record MetricAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
