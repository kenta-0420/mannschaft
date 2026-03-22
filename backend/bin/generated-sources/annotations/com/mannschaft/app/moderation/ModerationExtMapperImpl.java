package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.dto.SettingsHistoryResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsHistoryEntity;
import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
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
public class ModerationExtMapperImpl implements ModerationExtMapper {

    @Override
    public ViolationResponse toViolationResponse(UserViolationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long reportId = null;
        Long actionId = null;
        String reason = null;
        LocalDateTime expiresAt = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        reportId = entity.getReportId();
        actionId = entity.getActionId();
        reason = entity.getReason();
        expiresAt = entity.getExpiresAt();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();

        String violationType = entity.getViolationType().name();

        ViolationResponse violationResponse = new ViolationResponse( id, userId, reportId, actionId, violationType, reason, expiresAt, isActive, createdAt );

        return violationResponse;
    }

    @Override
    public List<ViolationResponse> toViolationResponseList(List<UserViolationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ViolationResponse> list = new ArrayList<ViolationResponse>( entities.size() );
        for ( UserViolationEntity userViolationEntity : entities ) {
            list.add( toViolationResponse( userViolationEntity ) );
        }

        return list;
    }

    @Override
    public AppealResponse toAppealResponse(ModerationAppealEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long reportId = null;
        Long actionId = null;
        String appealReason = null;
        Long reviewedBy = null;
        String reviewNote = null;
        LocalDateTime reviewedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        reportId = entity.getReportId();
        actionId = entity.getActionId();
        appealReason = entity.getAppealReason();
        reviewedBy = entity.getReviewedBy();
        reviewNote = entity.getReviewNote();
        reviewedAt = entity.getReviewedAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        AppealResponse appealResponse = new AppealResponse( id, userId, reportId, actionId, status, appealReason, reviewedBy, reviewNote, reviewedAt, createdAt );

        return appealResponse;
    }

    @Override
    public List<AppealResponse> toAppealResponseList(List<ModerationAppealEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AppealResponse> list = new ArrayList<AppealResponse>( entities.size() );
        for ( ModerationAppealEntity moderationAppealEntity : entities ) {
            list.add( toAppealResponse( moderationAppealEntity ) );
        }

        return list;
    }

    @Override
    public WarningReReviewResponse toWarningReReviewResponse(WarningReReviewEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long reportId = null;
        Long actionId = null;
        String reason = null;
        Long adminReviewedBy = null;
        String adminReviewNote = null;
        LocalDateTime adminReviewedAt = null;
        String escalationReason = null;
        Long systemAdminReviewedBy = null;
        String systemAdminReviewNote = null;
        LocalDateTime systemAdminReviewedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        reportId = entity.getReportId();
        actionId = entity.getActionId();
        reason = entity.getReason();
        adminReviewedBy = entity.getAdminReviewedBy();
        adminReviewNote = entity.getAdminReviewNote();
        adminReviewedAt = entity.getAdminReviewedAt();
        escalationReason = entity.getEscalationReason();
        systemAdminReviewedBy = entity.getSystemAdminReviewedBy();
        systemAdminReviewNote = entity.getSystemAdminReviewNote();
        systemAdminReviewedAt = entity.getSystemAdminReviewedAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        WarningReReviewResponse warningReReviewResponse = new WarningReReviewResponse( id, userId, reportId, actionId, reason, status, adminReviewedBy, adminReviewNote, adminReviewedAt, escalationReason, systemAdminReviewedBy, systemAdminReviewNote, systemAdminReviewedAt, createdAt );

        return warningReReviewResponse;
    }

    @Override
    public List<WarningReReviewResponse> toWarningReReviewResponseList(List<WarningReReviewEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<WarningReReviewResponse> list = new ArrayList<WarningReReviewResponse>( entities.size() );
        for ( WarningReReviewEntity warningReReviewEntity : entities ) {
            list.add( toWarningReReviewResponse( warningReReviewEntity ) );
        }

        return list;
    }

    @Override
    public YabaiUnflagResponse toYabaiUnflagResponse(YabaiUnflagRequestEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String reason = null;
        Long reviewedBy = null;
        String reviewNote = null;
        LocalDateTime reviewedAt = null;
        LocalDateTime nextEligibleAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        reason = entity.getReason();
        reviewedBy = entity.getReviewedBy();
        reviewNote = entity.getReviewNote();
        reviewedAt = entity.getReviewedAt();
        nextEligibleAt = entity.getNextEligibleAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        YabaiUnflagResponse yabaiUnflagResponse = new YabaiUnflagResponse( id, userId, reason, status, reviewedBy, reviewNote, reviewedAt, nextEligibleAt, createdAt );

        return yabaiUnflagResponse;
    }

    @Override
    public List<YabaiUnflagResponse> toYabaiUnflagResponseList(List<YabaiUnflagRequestEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<YabaiUnflagResponse> list = new ArrayList<YabaiUnflagResponse>( entities.size() );
        for ( YabaiUnflagRequestEntity yabaiUnflagRequestEntity : entities ) {
            list.add( toYabaiUnflagResponse( yabaiUnflagRequestEntity ) );
        }

        return list;
    }

    @Override
    public ModerationSettingsResponse toSettingsResponse(ModerationSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String settingKey = null;
        String settingValue = null;
        String description = null;
        Long updatedBy = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        settingKey = entity.getSettingKey();
        settingValue = entity.getSettingValue();
        description = entity.getDescription();
        updatedBy = entity.getUpdatedBy();
        updatedAt = entity.getUpdatedAt();

        ModerationSettingsResponse moderationSettingsResponse = new ModerationSettingsResponse( id, settingKey, settingValue, description, updatedBy, updatedAt );

        return moderationSettingsResponse;
    }

    @Override
    public List<ModerationSettingsResponse> toSettingsResponseList(List<ModerationSettingsEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ModerationSettingsResponse> list = new ArrayList<ModerationSettingsResponse>( entities.size() );
        for ( ModerationSettingsEntity moderationSettingsEntity : entities ) {
            list.add( toSettingsResponse( moderationSettingsEntity ) );
        }

        return list;
    }

    @Override
    public ModerationTemplateResponse toTemplateResponse(ModerationActionTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String actionType = null;
        String reason = null;
        String templateText = null;
        String language = null;
        Boolean isDefault = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        actionType = entity.getActionType();
        reason = entity.getReason();
        templateText = entity.getTemplateText();
        language = entity.getLanguage();
        isDefault = entity.getIsDefault();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        ModerationTemplateResponse moderationTemplateResponse = new ModerationTemplateResponse( id, name, actionType, reason, templateText, language, isDefault, createdBy, createdAt, updatedAt );

        return moderationTemplateResponse;
    }

    @Override
    public List<ModerationTemplateResponse> toTemplateResponseList(List<ModerationActionTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ModerationTemplateResponse> list = new ArrayList<ModerationTemplateResponse>( entities.size() );
        for ( ModerationActionTemplateEntity moderationActionTemplateEntity : entities ) {
            list.add( toTemplateResponse( moderationActionTemplateEntity ) );
        }

        return list;
    }

    @Override
    public InternalNoteResponse toInternalNoteResponse(ReportInternalNoteEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long reportId = null;
        Long authorId = null;
        String note = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        reportId = entity.getReportId();
        authorId = entity.getAuthorId();
        note = entity.getNote();
        createdAt = entity.getCreatedAt();

        InternalNoteResponse internalNoteResponse = new InternalNoteResponse( id, reportId, authorId, note, createdAt );

        return internalNoteResponse;
    }

    @Override
    public List<InternalNoteResponse> toInternalNoteResponseList(List<ReportInternalNoteEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<InternalNoteResponse> list = new ArrayList<InternalNoteResponse>( entities.size() );
        for ( ReportInternalNoteEntity reportInternalNoteEntity : entities ) {
            list.add( toInternalNoteResponse( reportInternalNoteEntity ) );
        }

        return list;
    }

    @Override
    public SettingsHistoryResponse toSettingsHistoryResponse(ModerationSettingsHistoryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String settingKey = null;
        String oldValue = null;
        String newValue = null;
        Long changedBy = null;
        LocalDateTime changedAt = null;

        id = entity.getId();
        settingKey = entity.getSettingKey();
        oldValue = entity.getOldValue();
        newValue = entity.getNewValue();
        changedBy = entity.getChangedBy();
        changedAt = entity.getChangedAt();

        SettingsHistoryResponse settingsHistoryResponse = new SettingsHistoryResponse( id, settingKey, oldValue, newValue, changedBy, changedAt );

        return settingsHistoryResponse;
    }
}
