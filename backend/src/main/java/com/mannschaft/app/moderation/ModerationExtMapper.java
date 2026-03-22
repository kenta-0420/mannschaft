package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * モデレーション拡張機能の Entity → DTO 変換マッパー。
 * 既存 ModerationMapper との競合を回避するため別名とする。
 */
@Mapper(componentModel = "spring")
public interface ModerationExtMapper {

    @Mapping(target = "violationType", expression = "java(entity.getViolationType().name())")
    ViolationResponse toViolationResponse(UserViolationEntity entity);

    List<ViolationResponse> toViolationResponseList(List<UserViolationEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    AppealResponse toAppealResponse(ModerationAppealEntity entity);

    List<AppealResponse> toAppealResponseList(List<ModerationAppealEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    WarningReReviewResponse toWarningReReviewResponse(WarningReReviewEntity entity);

    List<WarningReReviewResponse> toWarningReReviewResponseList(List<WarningReReviewEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    YabaiUnflagResponse toYabaiUnflagResponse(YabaiUnflagRequestEntity entity);

    List<YabaiUnflagResponse> toYabaiUnflagResponseList(List<YabaiUnflagRequestEntity> entities);

    ModerationSettingsResponse toSettingsResponse(ModerationSettingsEntity entity);

    List<ModerationSettingsResponse> toSettingsResponseList(List<ModerationSettingsEntity> entities);

    ModerationTemplateResponse toTemplateResponse(ModerationActionTemplateEntity entity);

    List<ModerationTemplateResponse> toTemplateResponseList(List<ModerationActionTemplateEntity> entities);

    InternalNoteResponse toInternalNoteResponse(ReportInternalNoteEntity entity);

    List<InternalNoteResponse> toInternalNoteResponseList(List<ReportInternalNoteEntity> entities);
}
