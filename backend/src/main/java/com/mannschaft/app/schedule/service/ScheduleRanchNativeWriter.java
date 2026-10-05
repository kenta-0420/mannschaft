package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;
import com.mannschaft.app.schedule.event.AttendanceRespondedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本人PERSONAL通常回答の源TX。代理・調査・他scopeは従来業務へ委ねる。 */
@Service
class ScheduleRanchNativeWriter {
    private final ScheduleRepository schedules;
    private final ScheduleAttendanceRepository responses;
    private final ScheduleDelegationService delegations;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Clock wallClock;
    private final ScheduleRanchCaptureTelemetry telemetry;
    ScheduleRanchNativeWriter(ScheduleRepository schedules,ScheduleAttendanceRepository responses,
            ScheduleDelegationService delegations,ApplicationEventPublisher events,JdbcTemplate jdbc,
            Clock clock,@Qualifier("wallClock") Clock wallClock,ScheduleRanchCaptureTelemetry telemetry) {
        this.schedules=schedules;this.responses=responses;this.delegations=delegations;this.events=events;
        this.jdbc=jdbc;this.clock=clock;this.wallClock=wallClock;this.telemetry=telemetry;
    }
    record Outcome(AttendanceResponse response,ScheduleRanchCapture capture) { }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Outcome respond(Long scheduleId,Long actor,AttendanceRequest request) {
        if(jdbc.queryForList("SELECT id FROM schedules WHERE id=? AND user_id=? AND team_id IS NULL "
                +"AND organization_id IS NULL AND deleted_at IS NULL FOR UPDATE",scheduleId,actor).isEmpty())
            throw new BusinessException(CommonErrorCode.COMMON_002);
        var schedule=schedules.findById(scheduleId).orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
        if(!Boolean.TRUE.equals(schedule.getAttendanceRequired())) throw new BusinessException(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        if(schedule.getAttendanceDeadline()!=null && LocalDateTime.now(wallClock).isAfter(schedule.getAttendanceDeadline()))
            throw new BusinessException(ScheduleErrorCode.ATTENDANCE_DEADLINE_PASSED);
        if(schedule.getCommentOption()==CommentOption.REQUIRED && (request.getComment()==null || request.getComment().isBlank()))
            throw new BusinessException(ScheduleErrorCode.COMMENT_REQUIRED);
        var status=EnumInputParser.parse(AttendanceStatus.class,request.getStatus(),"status");
        // 初loadの前に現在行をlockする。古いPCのentityを資格判定へ再利用しない。
        var ids=jdbc.queryForList("SELECT id FROM schedule_attendances WHERE schedule_id=? AND user_id=? FOR UPDATE",
                Long.class,scheduleId,actor);
        if(ids.isEmpty()) throw new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        if(ids.size()!=1) throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1,ids.size());
        var attendance=responses.findById(ids.getFirst()).orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
        var at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        ScheduleRanchCapture capture=null;
        try {
            if(status!=AttendanceStatus.UNDECIDED && attendance.freezeRanchFirstSelfResponse(at,actor)) {
                var payload=new ScheduleRanchRewardPayload(UuidV7.generate(),1,RanchRewardSourceType.ATTENDANCE_RESPONSE,
                        RanchRewardEnvelope.IdType.LONG,attendance.getId().toString(),RanchRewardEnvelope.ScopeType.PERSONAL,null,null,
                        RanchRewardEnvelope.ActorKind.USER,actor,null,actor,actor,at,RanchRewardEnvelope.Origin.SELF_RESPONSE,
                        new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.valueOf(status.name()),false,true));
                capture=new ScheduleRanchCapture(payload);
            }
        } catch(RuntimeException ignored) { telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.CAPTURE_FAILED); }
        attendance.respond(status,request.getComment());
        attendance=responses.saveAndFlush(attendance);
        delegations.onDelegatorAttendanceChanged(scheduleId,actor,status);
        events.publishEvent(new AttendanceRespondedEvent(scheduleId,actor,status.name()));
        return new Outcome(new AttendanceResponse(attendance.getId(),attendance.getUserId(),attendance.getStatus().name(),
                attendance.getComment(),attendance.getRespondedAt()),capture);
    }
}
