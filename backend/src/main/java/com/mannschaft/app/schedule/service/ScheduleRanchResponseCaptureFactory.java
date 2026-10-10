package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 既存回答TX内で有限値だけを捕捉する。報酬専用DB書込や追加TXは行わない。 */
@Component
@RequiredArgsConstructor
class ScheduleRanchResponseCaptureFactory {
    private final Clock clock;
    private final ScheduleRanchCaptureTelemetry telemetry;
    void capture(ScheduleEntity schedule, ScheduleAttendanceEntity response, Long actor,
            AttendanceStatus status, AttendanceRequest request) {
        try {
            if (status == AttendanceStatus.UNDECIDED
                    || !Boolean.TRUE.equals(response.getRanchResponseHistoryKnown())
                    || Boolean.TRUE.equals(response.getRanchSelfResponseObserved())
                    || !actor.equals(response.getUserId())) return;
            var scope = schedule.getTeamId() != null ? RanchRewardEnvelope.ScopeType.TEAM
                    : schedule.getOrganizationId() != null ? RanchRewardEnvelope.ScopeType.ORGANIZATION
                    : RanchRewardEnvelope.ScopeType.PERSONAL;
            Long scopeId = schedule.getTeamId() != null ? schedule.getTeamId() : schedule.getOrganizationId();
            var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var payload = new ScheduleRanchRewardPayload(UuidV7.generate(), 1,
                    RanchRewardSourceType.ATTENDANCE_RESPONSE, RanchRewardEnvelope.IdType.LONG,
                    response.getId().toString(), scope,
                    scopeId == null ? null : RanchRewardEnvelope.IdType.LONG,
                    scopeId == null ? null : scopeId.toString(), RanchRewardEnvelope.ActorKind.USER,
                    actor, null, actor, actor, at, RanchRewardEnvelope.Origin.SELF_RESPONSE,
                    new RanchRewardEnvelope.Attendance(
                            RanchRewardEnvelope.AttendanceStatus.valueOf(status.name()), false, true));
            if (response.freezeRanchFirstSelfResponse(at, actor)) request.recordRanchCapture(payload);
        } catch (RuntimeException ignored) {
            telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.CAPTURE_FAILED);
        }
    }
}
