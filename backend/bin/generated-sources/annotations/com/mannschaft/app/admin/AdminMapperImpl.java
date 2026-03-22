package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.admin.entity.NotificationDeliveryStatsEntity;
import java.time.LocalDate;
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
public class AdminMapperImpl implements AdminMapper {

    @Override
    public FeatureFlagResponse toFeatureFlagResponse(FeatureFlagEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String flagKey = null;
        Boolean isEnabled = null;
        String description = null;
        Long updatedBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        flagKey = entity.getFlagKey();
        isEnabled = entity.getIsEnabled();
        description = entity.getDescription();
        updatedBy = entity.getUpdatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        FeatureFlagResponse featureFlagResponse = new FeatureFlagResponse( id, flagKey, isEnabled, description, updatedBy, createdAt, updatedAt );

        return featureFlagResponse;
    }

    @Override
    public List<FeatureFlagResponse> toFeatureFlagResponseList(List<FeatureFlagEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FeatureFlagResponse> list = new ArrayList<FeatureFlagResponse>( entities.size() );
        for ( FeatureFlagEntity featureFlagEntity : entities ) {
            list.add( toFeatureFlagResponse( featureFlagEntity ) );
        }

        return list;
    }

    @Override
    public MaintenanceScheduleResponse toMaintenanceScheduleResponse(MaintenanceScheduleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        String message = null;
        LocalDateTime startsAt = null;
        LocalDateTime endsAt = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        title = entity.getTitle();
        message = entity.getMessage();
        startsAt = entity.getStartsAt();
        endsAt = entity.getEndsAt();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String mode = entity.getMode().name();
        String status = entity.getStatus().name();

        MaintenanceScheduleResponse maintenanceScheduleResponse = new MaintenanceScheduleResponse( id, title, message, mode, startsAt, endsAt, status, createdBy, createdAt, updatedAt );

        return maintenanceScheduleResponse;
    }

    @Override
    public List<MaintenanceScheduleResponse> toMaintenanceScheduleResponseList(List<MaintenanceScheduleEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MaintenanceScheduleResponse> list = new ArrayList<MaintenanceScheduleResponse>( entities.size() );
        for ( MaintenanceScheduleEntity maintenanceScheduleEntity : entities ) {
            list.add( toMaintenanceScheduleResponse( maintenanceScheduleEntity ) );
        }

        return list;
    }

    @Override
    public BatchJobLogResponse toBatchJobLogResponse(BatchJobLogEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String jobName = null;
        LocalDateTime startedAt = null;
        LocalDateTime finishedAt = null;
        Integer processedCount = null;
        String errorMessage = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        jobName = entity.getJobName();
        startedAt = entity.getStartedAt();
        finishedAt = entity.getFinishedAt();
        processedCount = entity.getProcessedCount();
        errorMessage = entity.getErrorMessage();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        BatchJobLogResponse batchJobLogResponse = new BatchJobLogResponse( id, jobName, status, startedAt, finishedAt, processedCount, errorMessage, createdAt );

        return batchJobLogResponse;
    }

    @Override
    public List<BatchJobLogResponse> toBatchJobLogResponseList(List<BatchJobLogEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BatchJobLogResponse> list = new ArrayList<BatchJobLogResponse>( entities.size() );
        for ( BatchJobLogEntity batchJobLogEntity : entities ) {
            list.add( toBatchJobLogResponse( batchJobLogEntity ) );
        }

        return list;
    }

    @Override
    public NotificationStatsResponse toNotificationStatsResponse(NotificationDeliveryStatsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        LocalDate date = null;
        Integer sentCount = null;
        Integer deliveredCount = null;
        Integer failedCount = null;
        Integer bounceCount = null;

        id = entity.getId();
        date = entity.getDate();
        sentCount = entity.getSentCount();
        deliveredCount = entity.getDeliveredCount();
        failedCount = entity.getFailedCount();
        bounceCount = entity.getBounceCount();

        String channel = entity.getChannel().name();

        NotificationStatsResponse notificationStatsResponse = new NotificationStatsResponse( id, date, channel, sentCount, deliveredCount, failedCount, bounceCount );

        return notificationStatsResponse;
    }

    @Override
    public List<NotificationStatsResponse> toNotificationStatsResponseList(List<NotificationDeliveryStatsEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<NotificationStatsResponse> list = new ArrayList<NotificationStatsResponse>( entities.size() );
        for ( NotificationDeliveryStatsEntity notificationDeliveryStatsEntity : entities ) {
            list.add( toNotificationStatsResponse( notificationDeliveryStatsEntity ) );
        }

        return list;
    }
}
