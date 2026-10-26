package com.mannschaft.app.performance;

import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.TemplateListResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import org.mapstruct.Mapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * パフォーマンス管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface PerformanceMapper {

    default MetricResponse toMetricResponse(PerformanceMetricEntity entity) {
        return MetricResponse.builder()
                .id(entity.getId())
                .definition(new MetricResponse.MetricDefinitionDto(
                        entity.getName(), entity.getUnit(),
                        entity.getDataType().name(), entity.getAggregationType().name(),
                        entity.getDescription(), entity.getGroupName()))
                .valueRange(new MetricResponse.MetricValueRangeDto(
                        entity.getTargetValue(), entity.getMinValue(), entity.getMaxValue()))
                .access(new MetricResponse.MetricAccessDto(
                        entity.getIsVisibleToMembers(), entity.getIsSelfRecordable(),
                        entity.getLinkedActivityFieldId(), entity.getIsActive()))
                .sortOrder(entity.getSortOrder())
                .audit(new MetricResponse.MetricAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default List<MetricResponse> toMetricResponseList(List<PerformanceMetricEntity> entities) {
        return entities.stream().map(this::toMetricResponse).collect(Collectors.toList());
    }

    /**
     * 記録エンティティをレスポンスに変換する。metricName と unit は呼び出し元で設定する。
     */
    default RecordResponse toRecordResponse(PerformanceRecordEntity entity, String metricName, String unit) {
        return RecordResponse.builder()
                .id(entity.getId())
                .metric(new RecordResponse.RecordMetricDto(entity.getMetricId(), metricName))
                .actor(new RecordResponse.RecordActorDto(
                        entity.getUserId(), entity.getScheduleId(), entity.getActivityResultId()))
                .record(new RecordResponse.RecordValueDto(
                        entity.getRecordedDate(), entity.getValue(), unit, entity.getNote()))
                .source(new RecordResponse.RecordSourceDto(entity.getSource().name(), entity.getRecordedBy()))
                .audit(new RecordResponse.RecordAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    default TemplateListResponse.TemplateMetric toTemplateMetric(PerformanceMetricTemplateEntity entity) {
        return new TemplateListResponse.TemplateMetric(
                entity.getId(),
                entity.getName(),
                entity.getUnit(),
                entity.getDataType().name(),
                entity.getAggregationType().name(),
                entity.getGroupName(),
                entity.getDescription(),
                entity.getMinValue(),
                entity.getMaxValue(),
                entity.getIsSelfRecordable()
        );
    }
}
