package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 活動記録機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ActivityMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "defaultVisibility", expression = "java(entity.getDefaultVisibility().name())")
    @Mapping(target = "fields", expression = "java(java.util.Collections.emptyList())")
    ActivityTemplateResponse toActivityTemplateResponse(ActivityTemplateEntity entity);

    List<ActivityTemplateResponse> toActivityTemplateResponseList(List<ActivityTemplateEntity> entities);

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    ActivityTemplateResponse.TemplateFieldResponse toTemplateFieldResponse(ActivityTemplateFieldEntity entity);

    List<ActivityTemplateResponse.TemplateFieldResponse> toTemplateFieldResponseList(List<ActivityTemplateFieldEntity> entities);

    @Mapping(target = "displayName", ignore = true)
    @Mapping(target = "memberNumber", ignore = true)
    ActivityParticipantResponse toParticipantResponse(ActivityParticipantEntity entity);

    List<ActivityParticipantResponse> toParticipantResponseList(List<ActivityParticipantEntity> entities);

    ActivityCommentResponse toCommentResponse(ActivityCommentEntity entity);

    List<ActivityCommentResponse> toCommentResponseList(List<ActivityCommentEntity> entities);

    @Mapping(target = "category", expression = "java(entity.getCategory().name())")
    PresetResponse toPresetResponse(SystemActivityTemplatePresetEntity entity);

    List<PresetResponse> toPresetResponseList(List<SystemActivityTemplatePresetEntity> entities);

    /**
     * 活動記録 Entity → DTO 変換。{@code deletedAt} と派生ゲッター {@code isPublishable()}
     * （{@code publishable}）は写像対象から除外し、レスポンスへの漏洩を止める。
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ActivityRecordResponse toActivityRecordResponse(ActivityResultEntity entity);

    List<ActivityRecordResponse> toActivityRecordResponseList(List<ActivityResultEntity> entities);
}
