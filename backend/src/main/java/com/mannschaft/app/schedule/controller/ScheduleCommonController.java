package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュール共通コントローラー。スコープ横断の出欠回答・集計・カレンダー・統計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "スケジュール共通", description = "F03.1 スコープ横断のスケジュール・出欠共通API")
@RequiredArgsConstructor
public class ScheduleCommonController {

    private final ScheduleService scheduleService;
    private final ScheduleAttendanceService attendanceService;
    private final ScheduleReminderService reminderService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 出欠回答を行う。
     */
    @PatchMapping("/schedules/{scheduleId}/responses")
    @Operation(summary = "出欠回答")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "回答成功")
    public ResponseEntity<ApiResponse<AttendanceResponse>> respondAttendance(
            @PathVariable Long scheduleId,
            @Valid @RequestBody AttendanceRequest request) {
        AttendanceResponse response = attendanceService.respondAttendance(
                scheduleId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 出欠集計サマリーを取得する。
     */
    @GetMapping("/schedules/{scheduleId}/stats")
    @Operation(summary = "出欠集計サマリー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AttendanceSummaryResponse>> getAttendanceStats(
            @PathVariable Long scheduleId) {
        AttendanceSummaryResponse response = attendanceService.getAttendanceSummary(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * リマインドを送信する。
     */
    @PostMapping("/schedules/{scheduleId}/remind")
    @Operation(summary = "リマインド送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "送信成功")
    public ResponseEntity<Void> sendReminder(
            @PathVariable Long scheduleId) {
        reminderService.sendReminder(scheduleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ユーザーの横断カレンダーを取得する。個人・チーム・組織スコープのスケジュールを統合して返す。
     */
    @GetMapping("/my/calendar")
    @Operation(summary = "横断カレンダー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CalendarEntryResponse>>> getMyCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<CalendarEntryResponse> responses = scheduleService.getMyCalendar(
                getCurrentUserId(), from, to);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームの出席率統計を取得する。
     */
    @GetMapping("/teams/{teamId}/attendance-stats")
    @Operation(summary = "チーム出席率統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceStatsResponse>>> getTeamAttendanceStats(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<AttendanceStatsResponse> responses = attendanceService.getTeamAttendanceStats(
                teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームの出席率統計をCSVエクスポートする。
     */
    @GetMapping("/teams/{teamId}/attendance-stats/export")
    @Operation(summary = "チーム統計CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportTeamAttendanceStats(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<AttendanceStatsResponse> stats = attendanceService.getTeamAttendanceStats(
                teamId, from, to);
        String csv = buildAttendanceStatsCsv(stats);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=team_" + teamId + "_attendance_stats.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * 組織の出席率統計を取得する。
     */
    @GetMapping("/organizations/{orgId}/attendance-stats")
    @Operation(summary = "組織出席率統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceStatsResponse>>> getOrgAttendanceStats(
            @PathVariable Long orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<AttendanceStatsResponse> responses = attendanceService.getOrgAttendanceStats(
                orgId, from, to);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 組織の出席率統計をCSVエクスポートする。
     */
    @GetMapping("/organizations/{orgId}/attendance-stats/export")
    @Operation(summary = "組織統計CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportOrgAttendanceStats(
            @PathVariable Long orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<AttendanceStatsResponse> stats = attendanceService.getOrgAttendanceStats(
                orgId, from, to);
        String csv = buildAttendanceStatsCsv(stats);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=org_" + orgId + "_attendance_stats.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * 個人の出席率統計を取得する。
     */
    @GetMapping("/me/attendance-stats")
    @Operation(summary = "個人出席率統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AttendanceStatsResponse>> getMyAttendanceStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        AttendanceStatsResponse response = attendanceService.getMyAttendanceStats(
                getCurrentUserId(), from, to);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 出席率統計をCSV文字列に変換する。
     */
    private String buildAttendanceStatsCsv(List<AttendanceStatsResponse> stats) {
        StringBuilder csv = new StringBuilder();
        csv.append("ユーザーID,スケジュール総数,出席数,欠席数,一部出席数,出席率\n");
        for (AttendanceStatsResponse s : stats) {
            csv.append(s.getUserId()).append(",")
                    .append(s.getTotalSchedules()).append(",")
                    .append(s.getAttended()).append(",")
                    .append(s.getAbsent()).append(",")
                    .append(s.getPartial()).append(",")
                    .append(s.getAttendanceRate()).append("\n");
        }
        return csv.toString();
    }
}
