package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityResultResponse;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.List;

/**
 * 活動記録機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ActivityMapper {

    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "templateName", ignore = true)
    ActivityResultResponse toActivityResultResponse(ActivityResultEntity entity);

    List<ActivityResultResponse> toActivityResultResponseList(List<ActivityResultEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "defaultVisibility", expression = "java(entity.getDefaultVisibility().name())")
    @Mapping(target = "fields", expression = "java(java.util.Collections.emptyList())")
    ActivityTemplateResponse toActivityTemplateResponse(ActivityTemplateEntity entity);

    List<ActivityTemplateResponse> toActivityTemplateResponseList(List<ActivityTemplateEntity> entities);

    @Mapping(target = "scope", expression = "java(entity.getScope().name())")
    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    ActivityTemplateResponse.TemplateFieldResponse toTemplateFieldResponse(ActivityTemplateFieldEntity entity);

    List<ActivityTemplateResponse.TemplateFieldResponse> toTemplateFieldResponseList(List<ActivityTemplateFieldEntity> entities);

    @Mapping(target = "participationType", expression = "java(entity.getParticipationType().name())")
    ActivityParticipantResponse toParticipantResponse(ActivityParticipantEntity entity);

    List<ActivityParticipantResponse> toParticipantResponseList(List<ActivityParticipantEntity> entities);

    ActivityCommentResponse toCommentResponse(ActivityCommentEntity entity);

    List<ActivityCommentResponse> toCommentResponseList(List<ActivityCommentEntity> entities);
}
