package com.mannschaft.app.performance;

import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.TemplateListResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * パフォーマンス管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface PerformanceMapper {

    @Mapping(target = "dataType", expression = "java(entity.getDataType().name())")
    @Mapping(target = "aggregationType", expression = "java(entity.getAggregationType().name())")
    MetricResponse toMetricResponse(PerformanceMetricEntity entity);

    List<MetricResponse> toMetricResponseList(List<PerformanceMetricEntity> entities);

    /**
     * 記録エンティティをレスポンスに変換する。metricName と unit は呼び出し元で設定する。
     */
    default RecordResponse toRecordResponse(PerformanceRecordEntity entity, String metricName, String unit) {
        return new RecordResponse(
                entity.getId(),
                entity.getMetricId(),
                metricName,
                entity.getUserId(),
                entity.getScheduleId(),
                entity.getActivityResultId(),
                entity.getRecordedDate(),
                entity.getValue(),
                unit,
                entity.getNote(),
                entity.getSource().name(),
                entity.getRecordedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
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
