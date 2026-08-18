package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.EventSurveyResponse;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.dto.ScheduleDetailResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseDetailResponse;
import com.mannschaft.app.schedule.entity.EventSurveyEntity;
import com.mannschaft.app.schedule.entity.EventSurveyResponseEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * スケジュール機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ScheduleMapper {

    @Mapping(target = "content.title", source = "title")
    @Mapping(target = "content.status", expression = "java(scheduleEntity.getStatus().name())")
    @Mapping(target = "content.eventType", expression = "java(scheduleEntity.getEventType().name())")
    @Mapping(target = "content.location", source = "location")
    @Mapping(target = "content.attendanceRequired", source = "attendanceRequired")
    @Mapping(target = "time.startAt", source = "startAt")
    @Mapping(target = "time.endAt", source = "endAt")
    @Mapping(target = "time.allDay", source = "allDay")
    @Mapping(target = "scope", ignore = true)
    @Mapping(target = "academic.eventCategory", ignore = true)
    @Mapping(target = "academic.academicYear", expression = "java(scheduleEntity.getAcademicYear() != null ? scheduleEntity.getAcademicYear().intValue() : null)")
    @Mapping(target = "academic.sourceScheduleId", source = "sourceScheduleId")
    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.createdByDisplayName", ignore = true)
    @Mapping(target = "myAttendanceStatus", ignore = true)
    @Mapping(target = "targets", ignore = true)
    @Mapping(target = "reminders", ignore = true)
    @Mapping(target = "scheduledTasks", ignore = true)
    ScheduleResponse toResponse(ScheduleEntity scheduleEntity);

    List<ScheduleResponse> toResponseList(List<ScheduleEntity> entities);

    @Mapping(target = "content.title", source = "title")
    @Mapping(target = "content.status", expression = "java(scheduleEntity.getStatus().name())")
    @Mapping(target = "content.eventType", expression = "java(scheduleEntity.getEventType().name())")
    @Mapping(target = "content.location", source = "location")
    @Mapping(target = "content.attendanceRequired", source = "attendanceRequired")
    @Mapping(target = "time.startAt", source = "startAt")
    @Mapping(target = "time.endAt", source = "endAt")
    @Mapping(target = "time.allDay", source = "allDay")
    @Mapping(target = "scope", ignore = true)
    @Mapping(target = "academic.eventCategory", ignore = true)
    @Mapping(target = "academic.academicYear", expression = "java(scheduleEntity.getAcademicYear() != null ? scheduleEntity.getAcademicYear().intValue() : null)")
    @Mapping(target = "academic.sourceScheduleId", source = "sourceScheduleId")
    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.createdByDisplayName", ignore = true)
    @Mapping(target = "myAttendanceStatus", ignore = true)
    @Mapping(target = "targets", ignore = true)
    @Mapping(target = "detail.description", source = "description")
    @Mapping(target = "detail.visibility", expression = "java(scheduleEntity.getVisibility().name())")
    @Mapping(target = "detail.color", source = "color")
    @Mapping(target = "detail.commentOption", expression = "java(scheduleEntity.getCommentOption() != null ? scheduleEntity.getCommentOption().name() : null)")
    @Mapping(target = "roles.minViewRole", expression = "java(scheduleEntity.getMinViewRole().name())")
    @Mapping(target = "roles.minResponseRole", expression = "java(scheduleEntity.getMinResponseRole() != null ? scheduleEntity.getMinResponseRole().name() : null)")
    @Mapping(target = "recurrence", ignore = true)
    @Mapping(target = "attendance", ignore = true)
    @Mapping(target = "relations", ignore = true)
    @Mapping(target = "reminders", ignore = true)
    @Mapping(target = "scheduledTasks", ignore = true)
    @Mapping(target = "createdBy", source = "createdBy")
    ScheduleDetailResponse toDetailResponse(ScheduleEntity scheduleEntity);

    @Mapping(target = "status", expression = "java(scheduleAttendanceEntity.getStatus().name())")
    AttendanceResponse toAttendanceResponse(ScheduleAttendanceEntity scheduleAttendanceEntity);

    List<AttendanceResponse> toAttendanceResponseList(List<ScheduleAttendanceEntity> entities);

    @Mapping(target = "questionType", expression = "java(eventSurveyEntity.getQuestionType().name())")
    @Mapping(target = "options", ignore = true)
    EventSurveyResponse toSurveyResponse(EventSurveyEntity eventSurveyEntity);

    List<EventSurveyResponse> toSurveyResponseList(List<EventSurveyEntity> entities);

    @Mapping(target = "reminderKind", expression = "java(entity.getReminderKind() != null ? entity.getReminderKind().name() : null)")
    @Mapping(target = "notified", ignore = true)
    ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity);

    List<ReminderResponse> toReminderResponseList(List<ScheduleAttendanceReminderEntity> entities);

    @Mapping(target = "target.targetType", expression = "java(scheduleCrossRefEntity.getTargetType().name())")
    @Mapping(target = "target.targetId", source = "targetId")
    @Mapping(target = "target.targetScheduleId", source = "targetScheduleId")
    @Mapping(target = "target.status", expression = "java(scheduleCrossRefEntity.getStatus().name())")
    @Mapping(target = "audit.invitedBy", source = "invitedBy")
    @Mapping(target = "audit.message", source = "message")
    @Mapping(target = "audit.createdAt", source = "createdAt")
    @Mapping(target = "audit.respondedAt", source = "respondedAt")
    CrossRefResponse toCrossRefResponse(ScheduleCrossRefEntity scheduleCrossRefEntity);

    List<CrossRefResponse> toCrossRefResponseList(List<ScheduleCrossRefEntity> entities);

    @Mapping(source = "eventSurveyId", target = "surveyId")
    @Mapping(target = "answerOptions", ignore = true)
    SurveyResponseDetailResponse toSurveyResponseDetailResponse(EventSurveyResponseEntity entity);
}
