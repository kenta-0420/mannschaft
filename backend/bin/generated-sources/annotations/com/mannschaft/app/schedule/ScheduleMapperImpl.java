package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.EventSurveyResponse;
import com.mannschaft.app.schedule.dto.RecurrenceRuleDto;
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
public class ScheduleMapperImpl implements ScheduleMapper {

    @Override
    public ScheduleResponse toResponse(ScheduleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        Boolean allDay = null;
        Boolean attendanceRequired = null;
        String location = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        title = entity.getTitle();
        startAt = entity.getStartAt();
        endAt = entity.getEndAt();
        allDay = entity.getAllDay();
        attendanceRequired = entity.getAttendanceRequired();
        location = entity.getLocation();
        createdAt = entity.getCreatedAt();

        String eventType = entity.getEventType().name();
        String status = entity.getStatus().name();

        ScheduleResponse scheduleResponse = new ScheduleResponse( id, title, startAt, endAt, allDay, eventType, status, attendanceRequired, location, createdAt );

        return scheduleResponse;
    }

    @Override
    public List<ScheduleResponse> toResponseList(List<ScheduleEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ScheduleResponse> list = new ArrayList<ScheduleResponse>( entities.size() );
        for ( ScheduleEntity scheduleEntity : entities ) {
            list.add( toResponse( scheduleEntity ) );
        }

        return list;
    }

    @Override
    public ScheduleDetailResponse toDetailResponse(ScheduleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        Boolean allDay = null;
        Boolean attendanceRequired = null;
        String location = null;
        LocalDateTime createdAt = null;
        String description = null;
        LocalDateTime attendanceDeadline = null;
        Boolean isException = null;
        Long parentScheduleId = null;
        String color = null;
        Long createdBy = null;

        id = entity.getId();
        title = entity.getTitle();
        startAt = entity.getStartAt();
        endAt = entity.getEndAt();
        allDay = entity.getAllDay();
        attendanceRequired = entity.getAttendanceRequired();
        location = entity.getLocation();
        createdAt = entity.getCreatedAt();
        description = entity.getDescription();
        attendanceDeadline = entity.getAttendanceDeadline();
        isException = entity.getIsException();
        parentScheduleId = entity.getParentScheduleId();
        color = entity.getColor();
        createdBy = entity.getCreatedBy();

        String eventType = entity.getEventType().name();
        String status = entity.getStatus().name();
        String visibility = entity.getVisibility().name();
        String minViewRole = entity.getMinViewRole().name();
        String minResponseRole = entity.getMinResponseRole() != null ? entity.getMinResponseRole().name() : null;
        String commentOption = entity.getCommentOption() != null ? entity.getCommentOption().name() : null;
        List<EventSurveyResponse> surveys = null;
        List<ReminderResponse> reminders = null;
        AttendanceResponse myAttendance = null;
        AttendanceSummaryResponse attendanceSummary = null;
        List<CrossRefResponse> crossInvitations = null;
        RecurrenceRuleDto recurrenceRule = null;

        ScheduleDetailResponse scheduleDetailResponse = new ScheduleDetailResponse( id, title, startAt, endAt, allDay, eventType, status, attendanceRequired, location, createdAt, description, visibility, minViewRole, minResponseRole, attendanceDeadline, commentOption, recurrenceRule, isException, parentScheduleId, color, createdBy, surveys, reminders, myAttendance, attendanceSummary, crossInvitations );

        return scheduleDetailResponse;
    }

    @Override
    public AttendanceResponse toAttendanceResponse(ScheduleAttendanceEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String comment = null;
        LocalDateTime respondedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        comment = entity.getComment();
        respondedAt = entity.getRespondedAt();

        String status = entity.getStatus().name();

        AttendanceResponse attendanceResponse = new AttendanceResponse( id, userId, status, comment, respondedAt );

        return attendanceResponse;
    }

    @Override
    public List<AttendanceResponse> toAttendanceResponseList(List<ScheduleAttendanceEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttendanceResponse> list = new ArrayList<AttendanceResponse>( entities.size() );
        for ( ScheduleAttendanceEntity scheduleAttendanceEntity : entities ) {
            list.add( toAttendanceResponse( scheduleAttendanceEntity ) );
        }

        return list;
    }

    @Override
    public EventSurveyResponse toSurveyResponse(EventSurveyEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String question = null;
        Boolean isRequired = null;
        Integer sortOrder = null;

        id = entity.getId();
        question = entity.getQuestion();
        isRequired = entity.getIsRequired();
        sortOrder = entity.getSortOrder();

        String questionType = entity.getQuestionType().name();
        List<String> options = null;

        EventSurveyResponse eventSurveyResponse = new EventSurveyResponse( id, question, questionType, options, isRequired, sortOrder );

        return eventSurveyResponse;
    }

    @Override
    public List<EventSurveyResponse> toSurveyResponseList(List<EventSurveyEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<EventSurveyResponse> list = new ArrayList<EventSurveyResponse>( entities.size() );
        for ( EventSurveyEntity eventSurveyEntity : entities ) {
            list.add( toSurveyResponse( eventSurveyEntity ) );
        }

        return list;
    }

    @Override
    public ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        LocalDateTime remindAt = null;
        Boolean isSent = null;
        LocalDateTime sentAt = null;

        id = entity.getId();
        remindAt = entity.getRemindAt();
        isSent = entity.getIsSent();
        sentAt = entity.getSentAt();

        ReminderResponse reminderResponse = new ReminderResponse( id, remindAt, isSent, sentAt );

        return reminderResponse;
    }

    @Override
    public List<ReminderResponse> toReminderResponseList(List<ScheduleAttendanceReminderEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReminderResponse> list = new ArrayList<ReminderResponse>( entities.size() );
        for ( ScheduleAttendanceReminderEntity scheduleAttendanceReminderEntity : entities ) {
            list.add( toReminderResponse( scheduleAttendanceReminderEntity ) );
        }

        return list;
    }

    @Override
    public CrossRefResponse toCrossRefResponse(ScheduleCrossRefEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long sourceScheduleId = null;
        Long targetId = null;
        Long targetScheduleId = null;
        String message = null;
        Long invitedBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime respondedAt = null;

        id = entity.getId();
        sourceScheduleId = entity.getSourceScheduleId();
        targetId = entity.getTargetId();
        targetScheduleId = entity.getTargetScheduleId();
        message = entity.getMessage();
        invitedBy = entity.getInvitedBy();
        createdAt = entity.getCreatedAt();
        respondedAt = entity.getRespondedAt();

        String targetType = entity.getTargetType().name();
        String status = entity.getStatus().name();

        CrossRefResponse crossRefResponse = new CrossRefResponse( id, sourceScheduleId, targetType, targetId, targetScheduleId, status, message, invitedBy, createdAt, respondedAt );

        return crossRefResponse;
    }

    @Override
    public List<CrossRefResponse> toCrossRefResponseList(List<ScheduleCrossRefEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CrossRefResponse> list = new ArrayList<CrossRefResponse>( entities.size() );
        for ( ScheduleCrossRefEntity scheduleCrossRefEntity : entities ) {
            list.add( toCrossRefResponse( scheduleCrossRefEntity ) );
        }

        return list;
    }

    @Override
    public SurveyResponseDetailResponse toSurveyResponseDetailResponse(EventSurveyResponseEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long surveyId = null;
        Long userId = null;
        String answerText = null;

        surveyId = entity.getEventSurveyId();
        userId = entity.getUserId();
        answerText = entity.getAnswerText();

        List<String> answerOptions = null;

        SurveyResponseDetailResponse surveyResponseDetailResponse = new SurveyResponseDetailResponse( surveyId, userId, answerText, answerOptions );

        return surveyResponseDetailResponse;
    }
}
