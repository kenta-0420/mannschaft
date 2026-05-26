package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 通知機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(target = "content.notificationType", source = "notificationType")
    @Mapping(target = "content.priority", expression = "java(entity.getPriority().name())")
    @Mapping(target = "content.title", source = "title")
    @Mapping(target = "content.body", source = "body")
    @Mapping(target = "content.actionUrl", source = "actionUrl")
    @Mapping(target = "source.sourceType", source = "sourceType")
    @Mapping(target = "source.sourceId", source = "sourceId")
    @Mapping(target = "scope.scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "scope.scopeId", source = "scopeId")
    @Mapping(target = "scope.actorId", source = "actorId")
    @Mapping(target = "status.isRead", source = "isRead")
    @Mapping(target = "status.readAt", source = "readAt")
    @Mapping(target = "status.channelsSent", source = "channelsSent")
    @Mapping(target = "status.snoozedUntil", source = "snoozedUntil")
    NotificationResponse toNotificationResponse(NotificationEntity entity);

    List<NotificationResponse> toNotificationResponseList(List<NotificationEntity> entities);

    @Mapping(target = "scope.scopeType", source = "scopeType")
    @Mapping(target = "scope.scopeId", source = "scopeId")
    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.updatedAt", source = "updatedAt")
    PreferenceResponse toPreferenceResponse(NotificationPreferenceEntity entity);

    List<PreferenceResponse> toPreferenceResponseList(List<NotificationPreferenceEntity> entities);

    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.updatedAt", source = "updatedAt")
    TypePreferenceResponse toTypePreferenceResponse(NotificationTypePreferenceEntity entity);

    List<TypePreferenceResponse> toTypePreferenceResponseList(List<NotificationTypePreferenceEntity> entities);
}
