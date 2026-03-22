package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:44:02+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class AnnouncementFeedbackMapperImpl implements AnnouncementFeedbackMapper {

    @Override
    public AnnouncementResponse toAnnouncementResponse(PlatformAnnouncementEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        String body = null;
        String priority = null;
        String targetScope = null;
        Boolean isPinned = null;
        LocalDateTime publishedAt = null;
        LocalDateTime expiresAt = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        title = entity.getTitle();
        body = entity.getBody();
        priority = entity.getPriority();
        targetScope = entity.getTargetScope();
        isPinned = entity.getIsPinned();
        publishedAt = entity.getPublishedAt();
        expiresAt = entity.getExpiresAt();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        AnnouncementResponse announcementResponse = new AnnouncementResponse( id, title, body, priority, targetScope, isPinned, publishedAt, expiresAt, createdBy, createdAt, updatedAt );

        return announcementResponse;
    }

    @Override
    public List<AnnouncementResponse> toAnnouncementResponseList(List<PlatformAnnouncementEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AnnouncementResponse> list = new ArrayList<AnnouncementResponse>( entities.size() );
        for ( PlatformAnnouncementEntity platformAnnouncementEntity : entities ) {
            list.add( toAnnouncementResponse( platformAnnouncementEntity ) );
        }

        return list;
    }

    @Override
    public FeedbackResponse toFeedbackResponse(FeedbackSubmissionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String category = null;
        String title = null;
        String body = null;
        Boolean isAnonymous = null;
        Long submittedBy = null;
        String status = null;
        String adminResponse = null;
        Long respondedBy = null;
        LocalDateTime respondedAt = null;
        Boolean isPublicResponse = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        category = entity.getCategory();
        title = entity.getTitle();
        body = entity.getBody();
        isAnonymous = entity.getIsAnonymous();
        submittedBy = entity.getSubmittedBy();
        if ( entity.getStatus() != null ) {
            status = entity.getStatus().name();
        }
        adminResponse = entity.getAdminResponse();
        respondedBy = entity.getRespondedBy();
        respondedAt = entity.getRespondedAt();
        isPublicResponse = entity.getIsPublicResponse();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        Long voteCount = null;

        FeedbackResponse feedbackResponse = new FeedbackResponse( id, scopeType, scopeId, category, title, body, isAnonymous, submittedBy, status, adminResponse, respondedBy, respondedAt, isPublicResponse, voteCount, createdAt, updatedAt );

        return feedbackResponse;
    }

    @Override
    public List<FeedbackResponse> toFeedbackResponseList(List<FeedbackSubmissionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FeedbackResponse> list = new ArrayList<FeedbackResponse>( entities.size() );
        for ( FeedbackSubmissionEntity feedbackSubmissionEntity : entities ) {
            list.add( toFeedbackResponse( feedbackSubmissionEntity ) );
        }

        return list;
    }

    @Override
    public ActionTemplateResponse toActionTemplateResponse(AdminActionTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String actionType = null;
        String reason = null;
        String templateText = null;
        Boolean isDefault = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        actionType = entity.getActionType();
        reason = entity.getReason();
        templateText = entity.getTemplateText();
        isDefault = entity.getIsDefault();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        ActionTemplateResponse actionTemplateResponse = new ActionTemplateResponse( id, name, actionType, reason, templateText, isDefault, createdBy, createdAt, updatedAt );

        return actionTemplateResponse;
    }

    @Override
    public List<ActionTemplateResponse> toActionTemplateResponseList(List<AdminActionTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ActionTemplateResponse> list = new ArrayList<ActionTemplateResponse>( entities.size() );
        for ( AdminActionTemplateEntity adminActionTemplateEntity : entities ) {
            list.add( toActionTemplateResponse( adminActionTemplateEntity ) );
        }

        return list;
    }
}
