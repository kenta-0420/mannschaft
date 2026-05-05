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

    @Mapping(target = "eventType", expression = "java(entity.getEventType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "recurrenceRule", ignore = true)
    @Mapping(target = "reminders", ignore = true)
    @Mapping(target = "googleSynced", expression = "java(entity.getGoogleCalendarEventId() != null)")
    @Mapping(target = "createdByDisplayName", ignore = true)
    PersonalScheduleResponse toResponse(ScheduleEntity entity);

    List<PersonalScheduleResponse> toResponseList(List<ScheduleEntity> entities);
}
