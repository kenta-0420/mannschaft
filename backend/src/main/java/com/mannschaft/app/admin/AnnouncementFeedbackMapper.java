package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * お知らせ・目安箱・テンプレートの Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface AnnouncementFeedbackMapper {

    AnnouncementResponse toAnnouncementResponse(PlatformAnnouncementEntity entity);

    List<AnnouncementResponse> toAnnouncementResponseList(List<PlatformAnnouncementEntity> entities);

    @Mapping(target = "voteCount", ignore = true)
    FeedbackResponse toFeedbackResponse(FeedbackSubmissionEntity entity);

    List<FeedbackResponse> toFeedbackResponseList(List<FeedbackSubmissionEntity> entities);

    ActionTemplateResponse toActionTemplateResponse(AdminActionTemplateEntity entity);

    List<ActionTemplateResponse> toActionTemplateResponseList(List<AdminActionTemplateEntity> entities);
}
