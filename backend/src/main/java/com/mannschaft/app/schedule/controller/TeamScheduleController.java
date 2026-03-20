package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.CrossInviteRequest;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.ScheduleDetailResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleCrossRefService;
import com.mannschaft.app.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * チームスケジュールコントローラー。チームスコープのスケジュールCRUD・出欠管理・クロス招待APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/schedules")
@Tag(name = "チームスケジュール管理", description = "F03.1 チームスコープのスケジュール・出欠管理")
@RequiredArgsConstructor
public class TeamScheduleController {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final ScheduleService scheduleService;
    private final ScheduleAttendanceService attendanceService;
    private final ScheduleCrossRefService crossRefService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームスケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームスケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleResponse>>> listSchedules(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        List<ScheduleResponse> schedules = scheduleService.listTeamSchedules(teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * チームスケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "チームスケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> createSchedule(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateScheduleRequest request) {
        ScheduleResponse response = scheduleService.createSchedule(
                request, teamId, SCOPE_TYPE_TEAM, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームスケジュール詳細を取得する。
     */
    @GetMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> getSchedule(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        // getScheduleWithAccessCheck は ScheduleEntity を返すため、Controller で ScheduleResponse に変換せず
        // Service 側の返却型に合わせて呼び出す。詳細表示は将来的に ScheduleDetailResponse に拡張予定。
        scheduleService.getScheduleWithAccessCheck(scheduleId, getCurrentUserId());
        // TODO: ScheduleDetailResponse への変換はMapper実装後に対応
        List<ScheduleResponse> list = scheduleService.listTeamSchedules(teamId,
                LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59));
        ScheduleResponse found = list.stream()
                .filter(s -> s.getId().equals(scheduleId))
                .findFirst()
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(found));
    }

    /**
     * チームスケジュールを更新する。
     */
    @PatchMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> updateSchedule(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        ScheduleResponse response = scheduleService.updateSchedule(
                scheduleId, request, updateScope, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームスケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "チームスケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        scheduleService.deleteSchedule(scheduleId, updateScope);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールをキャンセルする。
     */
    @PostMapping("/{scheduleId}/cancel")
    @Operation(summary = "チームスケジュールキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelSchedule(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        scheduleService.cancelSchedule(scheduleId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールの出欠一覧を取得する。
     */
    @GetMapping("/{scheduleId}/attendances")
    @Operation(summary = "チーム出欠一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getAttendances(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        List<AttendanceResponse> responses = attendanceService.getAttendances(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームスケジュールの出欠を一括更新する（管理者用）。
     */
    @PatchMapping("/{scheduleId}/attendances/bulk")
    @Operation(summary = "チーム出欠一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "更新成功")
    public ResponseEntity<Void> bulkUpdateAttendances(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody BulkAttendanceRequest request) {
        attendanceService.bulkUpdateAttendances(scheduleId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームスケジュールの出欠一覧をCSVエクスポートする。
     */
    @GetMapping("/{scheduleId}/attendances/export")
    @Operation(summary = "チーム出欠CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportAttendancesCsv(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        String csv = attendanceService.exportAttendancesCsv(scheduleId);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendances_" + scheduleId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * チームスケジュールを複製する。
     */
    @PostMapping("/{scheduleId}/duplicate")
    @Operation(summary = "チームスケジュール複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> duplicateSchedule(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        ScheduleResponse response = scheduleService.duplicateSchedule(scheduleId, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * クロスチーム・組織招待を送信する。
     */
    @PostMapping("/{scheduleId}/cross-invite")
    @Operation(summary = "クロス招待送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "招待送信成功")
    public ResponseEntity<ApiResponse<CrossRefResponse>> sendCrossInvite(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody CrossInviteRequest request) {
        CrossRefResponse response = crossRefService.sendCrossInvite(
                scheduleId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * クロス招待をキャンセルする。
     */
    @DeleteMapping("/{scheduleId}/cross-invite/{invitationId}")
    @Operation(summary = "クロス招待キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelCrossInvite(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @PathVariable Long invitationId) {
        crossRefService.cancelCrossInvite(invitationId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
