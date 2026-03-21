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

    @Mapping(target = "priority", expression = "java(entity.getPriority().name())")
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    NotificationResponse toNotificationResponse(NotificationEntity entity);

    List<NotificationResponse> toNotificationResponseList(List<NotificationEntity> entities);

    PreferenceResponse toPreferenceResponse(NotificationPreferenceEntity entity);

    List<PreferenceResponse> toPreferenceResponseList(List<NotificationPreferenceEntity> entities);

    TypePreferenceResponse toTypePreferenceResponse(NotificationTypePreferenceEntity entity);

    List<TypePreferenceResponse> toTypePreferenceResponseList(List<NotificationTypePreferenceEntity> entities);
}
