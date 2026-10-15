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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 安否確認機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SafetyCheckMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    SafetyCheckResponse toSafetyCheckResponse(SafetyCheckEntity entity);

    List<SafetyCheckResponse> toSafetyCheckResponseList(List<SafetyCheckEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "messageSource", expression = "java(entity.getMessageSource() != null ? entity.getMessageSource().name() : null)")
    SafetyResponseResponse toSafetyResponseResponse(SafetyResponseEntity entity);

    List<SafetyResponseResponse> toSafetyResponseResponseList(List<SafetyResponseEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType() != null ? entity.getScopeType().name() : null)")
    SafetyTemplateResponse toTemplateResponse(SafetyCheckTemplateEntity entity);

    List<SafetyTemplateResponse> toTemplateResponseList(List<SafetyCheckTemplateEntity> entities);

    SafetyPresetResponse toPresetResponse(SafetyCheckMessagePresetEntity entity);

    List<SafetyPresetResponse> toPresetResponseList(List<SafetyCheckMessagePresetEntity> entities);

    @Mapping(target = "followupStatus", expression = "java(entity.getFollowupStatus().name())")
    SafetyFollowupResponse toFollowupResponse(SafetyResponseFollowupEntity entity);

    List<SafetyFollowupResponse> toFollowupResponseList(List<SafetyResponseFollowupEntity> entities);
}
