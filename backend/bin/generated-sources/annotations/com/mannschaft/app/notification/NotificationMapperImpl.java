package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
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
public class NotificationMapperImpl implements NotificationMapper {

    @Override
    public NotificationResponse toNotificationResponse(NotificationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String notificationType = null;
        String title = null;
        String body = null;
        String sourceType = null;
        Long sourceId = null;
        Long scopeId = null;
        String actionUrl = null;
        Long actorId = null;
        Boolean isRead = null;
        LocalDateTime readAt = null;
        String channelsSent = null;
        LocalDateTime snoozedUntil = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        notificationType = entity.getNotificationType();
        title = entity.getTitle();
        body = entity.getBody();
        sourceType = entity.getSourceType();
        sourceId = entity.getSourceId();
        scopeId = entity.getScopeId();
        actionUrl = entity.getActionUrl();
        actorId = entity.getActorId();
        isRead = entity.getIsRead();
        readAt = entity.getReadAt();
        channelsSent = entity.getChannelsSent();
        snoozedUntil = entity.getSnoozedUntil();
        createdAt = entity.getCreatedAt();

        String priority = entity.getPriority().name();
        String scopeType = entity.getScopeType().name();

        NotificationResponse notificationResponse = new NotificationResponse( id, userId, notificationType, priority, title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, isRead, readAt, channelsSent, snoozedUntil, createdAt );

        return notificationResponse;
    }

    @Override
    public List<NotificationResponse> toNotificationResponseList(List<NotificationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<NotificationResponse> list = new ArrayList<NotificationResponse>( entities.size() );
        for ( NotificationEntity notificationEntity : entities ) {
            list.add( toNotificationResponse( notificationEntity ) );
        }

        return list;
    }

    @Override
    public PreferenceResponse toPreferenceResponse(NotificationPreferenceEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String scopeType = null;
        Long scopeId = null;
        Boolean isEnabled = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        isEnabled = entity.getIsEnabled();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        PreferenceResponse preferenceResponse = new PreferenceResponse( id, userId, scopeType, scopeId, isEnabled, createdAt, updatedAt );

        return preferenceResponse;
    }

    @Override
    public List<PreferenceResponse> toPreferenceResponseList(List<NotificationPreferenceEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PreferenceResponse> list = new ArrayList<PreferenceResponse>( entities.size() );
        for ( NotificationPreferenceEntity notificationPreferenceEntity : entities ) {
            list.add( toPreferenceResponse( notificationPreferenceEntity ) );
        }

        return list;
    }

    @Override
    public TypePreferenceResponse toTypePreferenceResponse(NotificationTypePreferenceEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String notificationType = null;
        Boolean isEnabled = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        notificationType = entity.getNotificationType();
        isEnabled = entity.getIsEnabled();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        TypePreferenceResponse typePreferenceResponse = new TypePreferenceResponse( id, userId, notificationType, isEnabled, createdAt, updatedAt );

        return typePreferenceResponse;
    }

    @Override
    public List<TypePreferenceResponse> toTypePreferenceResponseList(List<NotificationTypePreferenceEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TypePreferenceResponse> list = new ArrayList<TypePreferenceResponse>( entities.size() );
        for ( NotificationTypePreferenceEntity notificationTypePreferenceEntity : entities ) {
            list.add( toTypePreferenceResponse( notificationTypePreferenceEntity ) );
        }

        return list;
    }
}
