package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 保護callbackから既存回答proxyを一回だけ呼ぶ。認可・保存・イベントを複製しない。 */
@Service
@RequiredArgsConstructor
class ScheduleRanchNativeWriter {
    private final ScheduleAttendanceService attendanceService;
    record Outcome(AttendanceResponse response, ScheduleRanchCapture capture) { }
    Outcome respond(Long scheduleId, Long actor, AttendanceRequest request) {
        request.armRanchCapture();
        try {
            var response = attendanceService.respondAttendance(scheduleId, actor, request);
            var payload = request.takeRanchCapture();
            return new Outcome(response, payload == null ? null : new ScheduleRanchCapture(payload));
        } finally {
            request.takeRanchCapture();
        }
    }
}
