package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.admin.entity.NotificationDeliveryStatsEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 管理基盤機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface AdminMapper {

    FeatureFlagResponse toFeatureFlagResponse(FeatureFlagEntity entity);

    List<FeatureFlagResponse> toFeatureFlagResponseList(List<FeatureFlagEntity> entities);

    @Mapping(target = "mode", expression = "java(entity.getMode().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    MaintenanceScheduleResponse toMaintenanceScheduleResponse(MaintenanceScheduleEntity entity);

    List<MaintenanceScheduleResponse> toMaintenanceScheduleResponseList(List<MaintenanceScheduleEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    BatchJobLogResponse toBatchJobLogResponse(BatchJobLogEntity entity);

    List<BatchJobLogResponse> toBatchJobLogResponseList(List<BatchJobLogEntity> entities);

    @Mapping(target = "channel", expression = "java(entity.getChannel().name())")
    NotificationStatsResponse toNotificationStatsResponse(NotificationDeliveryStatsEntity entity);

    List<NotificationStatsResponse> toNotificationStatsResponseList(List<NotificationDeliveryStatsEntity> entities);
}
