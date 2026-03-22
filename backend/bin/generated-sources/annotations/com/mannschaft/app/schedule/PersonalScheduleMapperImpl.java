package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T17:23:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PersonalScheduleMapperImpl implements PersonalScheduleMapper {

    @Override
    public PersonalScheduleResponse toResponse(ScheduleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        String description = null;
        String location = null;
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        Boolean allDay = null;
        String color = null;
        Long parentScheduleId = null;
        Boolean isException = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        title = entity.getTitle();
        description = entity.getDescription();
        location = entity.getLocation();
        startAt = entity.getStartAt();
        endAt = entity.getEndAt();
        allDay = entity.getAllDay();
        color = entity.getColor();
        parentScheduleId = entity.getParentScheduleId();
        isException = entity.getIsException();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String eventType = entity.getEventType().name();
        String status = entity.getStatus().name();
        RecurrenceRuleDto recurrenceRule = null;
        List<Integer> reminders = null;
        boolean googleSynced = entity.getGoogleCalendarEventId() != null;

        PersonalScheduleResponse personalScheduleResponse = new PersonalScheduleResponse( id, title, description, location, startAt, endAt, allDay, eventType, color, status, parentScheduleId, recurrenceRule, isException, reminders, googleSynced, createdAt, updatedAt );

        return personalScheduleResponse;
    }

    @Override
    public List<PersonalScheduleResponse> toResponseList(List<ScheduleEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PersonalScheduleResponse> list = new ArrayList<PersonalScheduleResponse>( entities.size() );
        for ( ScheduleEntity scheduleEntity : entities ) {
            list.add( toResponse( scheduleEntity ) );
        }

        return list;
    }
}
