package com.mannschaft.app.safetycheck;

import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.dto.SafetyFollowupResponse;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckMessagePresetEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SafetyCheckMapperImpl implements SafetyCheckMapper {

    @Override
    public SafetyCheckResponse toSafetyCheckResponse(SafetyCheckEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String title = null;
        String message = null;
        Boolean isDrill = null;
        Integer reminderIntervalMinutes = null;
        Integer totalTargetCount = null;
        Long createdBy = null;
        LocalDateTime closedAt = null;
        Long closedBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        title = entity.getTitle();
        message = entity.getMessage();
        isDrill = entity.getIsDrill();
        reminderIntervalMinutes = entity.getReminderIntervalMinutes();
        totalTargetCount = entity.getTotalTargetCount();
        createdBy = entity.getCreatedBy();
        closedAt = entity.getClosedAt();
        closedBy = entity.getClosedBy();
        createdAt = entity.getCreatedAt();

        String scopeType = entity.getScopeType().name();
        String status = entity.getStatus().name();

        SafetyCheckResponse safetyCheckResponse = new SafetyCheckResponse( id, scopeType, scopeId, title, message, isDrill, status, reminderIntervalMinutes, totalTargetCount, createdBy, closedAt, closedBy, createdAt );

        return safetyCheckResponse;
    }

    @Override
    public List<SafetyCheckResponse> toSafetyCheckResponseList(List<SafetyCheckEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SafetyCheckResponse> list = new ArrayList<SafetyCheckResponse>( entities.size() );
        for ( SafetyCheckEntity safetyCheckEntity : entities ) {
            list.add( toSafetyCheckResponse( safetyCheckEntity ) );
        }

        return list;
    }

    @Override
    public SafetyResponseResponse toSafetyResponseResponse(SafetyResponseEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long safetyCheckId = null;
        Long userId = null;
        String message = null;
        Boolean gpsShared = null;
        BigDecimal gpsLatitude = null;
        BigDecimal gpsLongitude = null;
        LocalDateTime respondedAt = null;

        id = entity.getId();
        safetyCheckId = entity.getSafetyCheckId();
        userId = entity.getUserId();
        message = entity.getMessage();
        gpsShared = entity.getGpsShared();
        gpsLatitude = entity.getGpsLatitude();
        gpsLongitude = entity.getGpsLongitude();
        respondedAt = entity.getRespondedAt();

        String status = entity.getStatus().name();
        String messageSource = entity.getMessageSource() != null ? entity.getMessageSource().name() : null;

        SafetyResponseResponse safetyResponseResponse = new SafetyResponseResponse( id, safetyCheckId, userId, status, message, messageSource, gpsShared, gpsLatitude, gpsLongitude, respondedAt );

        return safetyResponseResponse;
    }

    @Override
    public List<SafetyResponseResponse> toSafetyResponseResponseList(List<SafetyResponseEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SafetyResponseResponse> list = new ArrayList<SafetyResponseResponse>( entities.size() );
        for ( SafetyResponseEntity safetyResponseEntity : entities ) {
            list.add( toSafetyResponseResponse( safetyResponseEntity ) );
        }

        return list;
    }

    @Override
    public SafetyTemplateResponse toTemplateResponse(SafetyCheckTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String templateName = null;
        String title = null;
        String message = null;
        Integer reminderIntervalMinutes = null;
        Boolean isSystemDefault = null;
        Integer sortOrder = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        templateName = entity.getTemplateName();
        title = entity.getTitle();
        message = entity.getMessage();
        reminderIntervalMinutes = entity.getReminderIntervalMinutes();
        isSystemDefault = entity.getIsSystemDefault();
        sortOrder = entity.getSortOrder();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();

        String scopeType = entity.getScopeType() != null ? entity.getScopeType().name() : null;

        SafetyTemplateResponse safetyTemplateResponse = new SafetyTemplateResponse( id, scopeType, scopeId, templateName, title, message, reminderIntervalMinutes, isSystemDefault, sortOrder, createdBy, createdAt );

        return safetyTemplateResponse;
    }

    @Override
    public List<SafetyTemplateResponse> toTemplateResponseList(List<SafetyCheckTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SafetyTemplateResponse> list = new ArrayList<SafetyTemplateResponse>( entities.size() );
        for ( SafetyCheckTemplateEntity safetyCheckTemplateEntity : entities ) {
            list.add( toTemplateResponse( safetyCheckTemplateEntity ) );
        }

        return list;
    }

    @Override
    public SafetyPresetResponse toPresetResponse(SafetyCheckMessagePresetEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String body = null;
        Integer sortOrder = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        body = entity.getBody();
        sortOrder = entity.getSortOrder();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();

        SafetyPresetResponse safetyPresetResponse = new SafetyPresetResponse( id, body, sortOrder, isActive, createdAt );

        return safetyPresetResponse;
    }

    @Override
    public List<SafetyPresetResponse> toPresetResponseList(List<SafetyCheckMessagePresetEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SafetyPresetResponse> list = new ArrayList<SafetyPresetResponse>( entities.size() );
        for ( SafetyCheckMessagePresetEntity safetyCheckMessagePresetEntity : entities ) {
            list.add( toPresetResponse( safetyCheckMessagePresetEntity ) );
        }

        return list;
    }

    @Override
    public SafetyFollowupResponse toFollowupResponse(SafetyResponseFollowupEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long safetyResponseId = null;
        Long assignedTo = null;
        String note = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        safetyResponseId = entity.getSafetyResponseId();
        assignedTo = entity.getAssignedTo();
        note = entity.getNote();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String followupStatus = entity.getFollowupStatus().name();

        SafetyFollowupResponse safetyFollowupResponse = new SafetyFollowupResponse( id, safetyResponseId, followupStatus, assignedTo, note, createdAt, updatedAt );

        return safetyFollowupResponse;
    }

    @Override
    public List<SafetyFollowupResponse> toFollowupResponseList(List<SafetyResponseFollowupEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SafetyFollowupResponse> list = new ArrayList<SafetyFollowupResponse>( entities.size() );
        for ( SafetyResponseFollowupEntity safetyResponseFollowupEntity : entities ) {
            list.add( toFollowupResponse( safetyResponseFollowupEntity ) );
        }

        return list;
    }
}
