package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:08+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ActivityMapperImpl implements ActivityMapper {

    @Override
    public ActivityTemplateResponse toActivityTemplateResponse(ActivityTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String name = null;
        String description = null;
        String icon = null;
        String color = null;
        Boolean isParticipantRequired = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        name = entity.getName();
        description = entity.getDescription();
        icon = entity.getIcon();
        color = entity.getColor();
        isParticipantRequired = entity.getIsParticipantRequired();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String defaultVisibility = entity.getDefaultVisibility().name();
        List<ActivityTemplateResponse.TemplateFieldResponse> fields = java.util.Collections.emptyList();

        ActivityTemplateResponse activityTemplateResponse = new ActivityTemplateResponse( id, scopeType, scopeId, name, description, icon, color, isParticipantRequired, defaultVisibility, sortOrder, fields, createdAt, updatedAt );

        return activityTemplateResponse;
    }

    @Override
    public List<ActivityTemplateResponse> toActivityTemplateResponseList(List<ActivityTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ActivityTemplateResponse> list = new ArrayList<ActivityTemplateResponse>( entities.size() );
        for ( ActivityTemplateEntity activityTemplateEntity : entities ) {
            list.add( toActivityTemplateResponse( activityTemplateEntity ) );
        }

        return list;
    }

    @Override
    public ActivityTemplateResponse.TemplateFieldResponse toTemplateFieldResponse(ActivityTemplateFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String fieldKey = null;
        String fieldLabel = null;
        Boolean isRequired = null;
        String optionsJson = null;
        String placeholder = null;
        String unit = null;
        Boolean isAggregatable = null;
        Integer sortOrder = null;

        id = entity.getId();
        fieldKey = entity.getFieldKey();
        fieldLabel = entity.getFieldLabel();
        isRequired = entity.getIsRequired();
        optionsJson = entity.getOptionsJson();
        placeholder = entity.getPlaceholder();
        unit = entity.getUnit();
        isAggregatable = entity.getIsAggregatable();
        sortOrder = entity.getSortOrder();

        String fieldType = entity.getFieldType().name();

        ActivityTemplateResponse.TemplateFieldResponse templateFieldResponse = new ActivityTemplateResponse.TemplateFieldResponse( id, fieldKey, fieldLabel, fieldType, isRequired, optionsJson, placeholder, unit, isAggregatable, sortOrder );

        return templateFieldResponse;
    }

    @Override
    public List<ActivityTemplateResponse.TemplateFieldResponse> toTemplateFieldResponseList(List<ActivityTemplateFieldEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ActivityTemplateResponse.TemplateFieldResponse> list = new ArrayList<ActivityTemplateResponse.TemplateFieldResponse>( entities.size() );
        for ( ActivityTemplateFieldEntity activityTemplateFieldEntity : entities ) {
            list.add( toTemplateFieldResponse( activityTemplateFieldEntity ) );
        }

        return list;
    }

    @Override
    public ActivityParticipantResponse toParticipantResponse(ActivityParticipantEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String roleLabel = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        roleLabel = entity.getRoleLabel();
        createdAt = entity.getCreatedAt();

        String displayName = null;
        String memberNumber = null;

        ActivityParticipantResponse activityParticipantResponse = new ActivityParticipantResponse( id, userId, displayName, memberNumber, roleLabel, createdAt );

        return activityParticipantResponse;
    }

    @Override
    public List<ActivityParticipantResponse> toParticipantResponseList(List<ActivityParticipantEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ActivityParticipantResponse> list = new ArrayList<ActivityParticipantResponse>( entities.size() );
        for ( ActivityParticipantEntity activityParticipantEntity : entities ) {
            list.add( toParticipantResponse( activityParticipantEntity ) );
        }

        return list;
    }

    @Override
    public ActivityCommentResponse toCommentResponse(ActivityCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long activityResultId = null;
        Long userId = null;
        String body = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        activityResultId = entity.getActivityResultId();
        userId = entity.getUserId();
        body = entity.getBody();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        ActivityCommentResponse activityCommentResponse = new ActivityCommentResponse( id, activityResultId, userId, body, createdAt, updatedAt );

        return activityCommentResponse;
    }

    @Override
    public List<ActivityCommentResponse> toCommentResponseList(List<ActivityCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ActivityCommentResponse> list = new ArrayList<ActivityCommentResponse>( entities.size() );
        for ( ActivityCommentEntity activityCommentEntity : entities ) {
            list.add( toCommentResponse( activityCommentEntity ) );
        }

        return list;
    }

    @Override
    public PresetResponse toPresetResponse(SystemActivityTemplatePresetEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String description = null;
        String icon = null;
        String color = null;
        Boolean isParticipantRequired = null;
        String defaultVisibility = null;
        String fieldsJson = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        description = entity.getDescription();
        icon = entity.getIcon();
        color = entity.getColor();
        isParticipantRequired = entity.getIsParticipantRequired();
        defaultVisibility = entity.getDefaultVisibility();
        fieldsJson = entity.getFieldsJson();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String category = entity.getCategory().name();

        PresetResponse presetResponse = new PresetResponse( id, category, name, description, icon, color, isParticipantRequired, defaultVisibility, fieldsJson, isActive, createdAt, updatedAt );

        return presetResponse;
    }

    @Override
    public List<PresetResponse> toPresetResponseList(List<SystemActivityTemplatePresetEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PresetResponse> list = new ArrayList<PresetResponse>( entities.size() );
        for ( SystemActivityTemplatePresetEntity systemActivityTemplatePresetEntity : entities ) {
            list.add( toPresetResponse( systemActivityTemplatePresetEntity ) );
        }

        return list;
    }
}
