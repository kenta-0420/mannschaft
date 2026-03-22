package com.mannschaft.app.performance;

import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PerformanceMapperImpl implements PerformanceMapper {

    @Override
    public MetricResponse toMetricResponse(PerformanceMetricEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String unit = null;
        String description = null;
        String groupName = null;
        BigDecimal targetValue = null;
        BigDecimal minValue = null;
        BigDecimal maxValue = null;
        Integer sortOrder = null;
        Boolean isVisibleToMembers = null;
        Boolean isSelfRecordable = null;
        Long linkedActivityFieldId = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        unit = entity.getUnit();
        description = entity.getDescription();
        groupName = entity.getGroupName();
        targetValue = entity.getTargetValue();
        minValue = entity.getMinValue();
        maxValue = entity.getMaxValue();
        sortOrder = entity.getSortOrder();
        isVisibleToMembers = entity.getIsVisibleToMembers();
        isSelfRecordable = entity.getIsSelfRecordable();
        linkedActivityFieldId = entity.getLinkedActivityFieldId();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String dataType = entity.getDataType().name();
        String aggregationType = entity.getAggregationType().name();

        MetricResponse metricResponse = new MetricResponse( id, name, unit, dataType, aggregationType, description, groupName, targetValue, minValue, maxValue, sortOrder, isVisibleToMembers, isSelfRecordable, linkedActivityFieldId, isActive, createdAt, updatedAt );

        return metricResponse;
    }

    @Override
    public List<MetricResponse> toMetricResponseList(List<PerformanceMetricEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MetricResponse> list = new ArrayList<MetricResponse>( entities.size() );
        for ( PerformanceMetricEntity performanceMetricEntity : entities ) {
            list.add( toMetricResponse( performanceMetricEntity ) );
        }

        return list;
    }
}
