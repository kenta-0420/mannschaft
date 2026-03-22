package com.mannschaft.app.line;

import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.LineMessageLogResponse;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import java.time.LocalDateTime;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class LineMapperImpl implements LineMapper {

    @Override
    public LineBotConfigResponse toLineBotConfigResponse(LineBotConfigEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String channelId = null;
        String webhookSecret = null;
        String botUserId = null;
        Boolean isActive = null;
        Boolean notificationEnabled = null;
        Long configuredBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        channelId = entity.getChannelId();
        webhookSecret = entity.getWebhookSecret();
        botUserId = entity.getBotUserId();
        isActive = entity.getIsActive();
        notificationEnabled = entity.getNotificationEnabled();
        configuredBy = entity.getConfiguredBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();

        LineBotConfigResponse lineBotConfigResponse = new LineBotConfigResponse( id, scopeType, scopeId, channelId, webhookSecret, botUserId, isActive, notificationEnabled, configuredBy, createdAt, updatedAt );

        return lineBotConfigResponse;
    }

    @Override
    public LineMessageLogResponse toLineMessageLogResponse(LineMessageLogEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long lineBotConfigId = null;
        String lineUserId = null;
        String contentSummary = null;
        String lineMessageId = null;
        String errorDetail = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        lineBotConfigId = entity.getLineBotConfigId();
        lineUserId = entity.getLineUserId();
        contentSummary = entity.getContentSummary();
        lineMessageId = entity.getLineMessageId();
        errorDetail = entity.getErrorDetail();
        createdAt = entity.getCreatedAt();

        String direction = entity.getDirection().name();
        String messageType = entity.getMessageType().name();
        String status = entity.getStatus().name();

        LineMessageLogResponse lineMessageLogResponse = new LineMessageLogResponse( id, lineBotConfigId, direction, messageType, lineUserId, contentSummary, lineMessageId, status, errorDetail, createdAt );

        return lineMessageLogResponse;
    }

    @Override
    public UserLineStatusResponse toUserLineStatusResponse(UserLineConnectionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String lineUserId = null;
        String displayName = null;
        String pictureUrl = null;
        String statusMessage = null;
        Boolean isActive = null;
        LocalDateTime linkedAt = null;

        lineUserId = entity.getLineUserId();
        displayName = entity.getDisplayName();
        pictureUrl = entity.getPictureUrl();
        statusMessage = entity.getStatusMessage();
        isActive = entity.getIsActive();
        linkedAt = entity.getLinkedAt();

        Boolean isLinked = true;

        UserLineStatusResponse userLineStatusResponse = new UserLineStatusResponse( isLinked, lineUserId, displayName, pictureUrl, statusMessage, isActive, linkedAt );

        return userLineStatusResponse;
    }

    @Override
    public SnsFeedConfigResponse toSnsFeedConfigResponse(SnsFeedConfigEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String accountUsername = null;
        Short displayCount = null;
        Boolean isActive = null;
        Long configuredBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        accountUsername = entity.getAccountUsername();
        displayCount = entity.getDisplayCount();
        isActive = entity.getIsActive();
        configuredBy = entity.getConfiguredBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String provider = entity.getProvider().name();

        SnsFeedConfigResponse snsFeedConfigResponse = new SnsFeedConfigResponse( id, scopeType, scopeId, provider, accountUsername, displayCount, isActive, configuredBy, createdAt, updatedAt );

        return snsFeedConfigResponse;
    }
}
