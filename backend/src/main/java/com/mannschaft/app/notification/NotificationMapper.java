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

    /**
     * Enum → String 変換（priority / scopeType）を含むため、
     * MapStruct の expression がネスト記法のサブメソッドでスコープ外になる問題を回避すべく
     * default メソッドで手動実装する。
     */
    default NotificationResponse toNotificationResponse(NotificationEntity entity) {
        if (entity == null) {
            return null;
        }
        return NotificationResponse.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .notificationType(entity.getNotificationType())
                .priority(entity.getPriority().name())
                .title(entity.getTitle())
                .body(entity.getBody())
                .actionUrl(entity.getActionUrl())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
                .scopeType(entity.getScopeType().name())
                .scopeId(entity.getScopeId())
                .actorId(entity.getActorId())
                .isRead(entity.getIsRead())
                .readAt(entity.getReadAt())
                .channelsSent(entity.getChannelsSent())
                .snoozedUntil(entity.getSnoozedUntil())
                .createdAt(entity.getCreatedAt())
                .build();
    }

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
