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
                .content(new NotificationResponse.NotificationContentDto(
                        entity.getNotificationType(),
                        entity.getPriority().name(),
                        entity.getTitle(),
                        entity.getBody(),
                        entity.getActionUrl()))
                .source(new NotificationResponse.NotificationSourceDto(
                        entity.getSourceType(),
                        entity.getSourceId()))
                .scope(new NotificationResponse.NotificationScopeDto(
                        entity.getScopeType().name(),
                        entity.getScopeId(),
                        entity.getActorId()))
                .status(new NotificationResponse.NotificationStatusDto(
                        entity.getIsRead(),
                        entity.getReadAt(),
                        entity.getChannelsSent(),
                        entity.getSnoozedUntil()))
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
