package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 個人スケジュール機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface PersonalScheduleMapper {

    @Mapping(target = "content.title", source = "title")
    @Mapping(target = "content.description", source = "description")
    @Mapping(target = "content.eventType", expression = "java(scheduleEntity.getEventType().name())")
    @Mapping(target = "content.color", source = "color")
    @Mapping(target = "content.location", source = "location")
    @Mapping(target = "time.startAt", source = "startAt")
    @Mapping(target = "time.endAt", source = "endAt")
    @Mapping(target = "time.allDay", source = "allDay")
    @Mapping(target = "status.status", expression = "java(scheduleEntity.getStatus().name())")
    @Mapping(target = "status.isException", source = "isException")
    @Mapping(target = "status.parentScheduleId", source = "parentScheduleId")
    @Mapping(target = "status.recurrenceRule", ignore = true)
    @Mapping(target = "status.googleSynced", expression = "java(scheduleEntity.getGoogleCalendarEventId() != null)")
    @Mapping(target = "reminders", ignore = true)
    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.updatedAt", source = "updatedAt")
    @Mapping(target = "audit.createdByDisplayName", ignore = true)
    PersonalScheduleResponse toResponse(ScheduleEntity scheduleEntity);

    List<PersonalScheduleResponse> toResponseList(List<ScheduleEntity> entities);
}
