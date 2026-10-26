package com.mannschaft.app.performance.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * パフォーマンス記録レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class RecordResponse {

    private final Long id;
    private final RecordMetricDto metric;
    private final RecordActorDto actor;
    private final RecordValueDto record;
    private final RecordSourceDto source;
    private final RecordAuditDto audit;

    public record RecordMetricDto(
            Long metricId,
            String metricName) {}

    public record RecordActorDto(
            Long userId,
            Long scheduleId,
            Long activityResultId) {}

    public record RecordValueDto(
            LocalDate recordedDate,
            BigDecimal value,
            String unit,
            String note) {}

    public record RecordSourceDto(
            String source,
            Long recordedBy) {}

    public record RecordAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
