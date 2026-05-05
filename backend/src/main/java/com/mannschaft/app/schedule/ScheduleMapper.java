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

    @Mapping(target = "eventType", expression = "java(entity.getEventType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "eventCategory", ignore = true)
    @Mapping(target = "academicYear", expression = "java(entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null)")
    @Mapping(target = "createdByDisplayName", ignore = true)
    @Mapping(target = "scopeName", ignore = true)
    @Mapping(target = "scopeIconUrl", ignore = true)
    ScheduleResponse toResponse(ScheduleEntity entity);

    List<ScheduleResponse> toResponseList(List<ScheduleEntity> entities);

    @Mapping(target = "eventType", expression = "java(entity.getEventType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "minViewRole", expression = "java(entity.getMinViewRole().name())")
    @Mapping(target = "minResponseRole", expression = "java(entity.getMinResponseRole() != null ? entity.getMinResponseRole().name() : null)")
    @Mapping(target = "commentOption", expression = "java(entity.getCommentOption() != null ? entity.getCommentOption().name() : null)")
    @Mapping(target = "surveys", ignore = true)
    @Mapping(target = "reminders", ignore = true)
    @Mapping(target = "myAttendance", ignore = true)
    @Mapping(target = "attendanceSummary", ignore = true)
    @Mapping(target = "crossInvitations", ignore = true)
    @Mapping(target = "recurrenceRule", ignore = true)
    @Mapping(target = "eventCategory", ignore = true)
    @Mapping(target = "academicYear", expression = "java(entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null)")
    ScheduleDetailResponse toDetailResponse(ScheduleEntity entity);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    AttendanceResponse toAttendanceResponse(ScheduleAttendanceEntity entity);

    List<AttendanceResponse> toAttendanceResponseList(List<ScheduleAttendanceEntity> entities);

    @Mapping(target = "questionType", expression = "java(entity.getQuestionType().name())")
    @Mapping(target = "options", ignore = true)
    EventSurveyResponse toSurveyResponse(EventSurveyEntity entity);

    List<EventSurveyResponse> toSurveyResponseList(List<EventSurveyEntity> entities);

    ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity);

    List<ReminderResponse> toReminderResponseList(List<ScheduleAttendanceReminderEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    CrossRefResponse toCrossRefResponse(ScheduleCrossRefEntity entity);

    List<CrossRefResponse> toCrossRefResponseList(List<ScheduleCrossRefEntity> entities);

    @Mapping(source = "eventSurveyId", target = "surveyId")
    @Mapping(target = "answerOptions", ignore = true)
    SurveyResponseDetailResponse toSurveyResponseDetailResponse(EventSurveyResponseEntity entity);
}
