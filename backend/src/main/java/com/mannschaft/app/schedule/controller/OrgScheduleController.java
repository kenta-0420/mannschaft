package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織スケジュールコントローラー。組織スコープのスケジュールCRUD・出欠管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/schedules")
@Tag(name = "組織スケジュール管理", description = "F03.1 組織スコープのスケジュール・出欠管理")
@RequiredArgsConstructor
public class OrgScheduleController {

    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    private final ScheduleService scheduleService;
    private final ScheduleAttendanceService attendanceService;


    /**
     * 組織スケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織スケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleResponse>>> listSchedules(
            @PathVariable Long orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        List<ScheduleResponse> schedules = scheduleService.listOrgSchedules(orgId, from, to);
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * 組織スケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "組織スケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> createSchedule(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateScheduleRequest request) {
        ScheduleResponse response = scheduleService.createSchedule(
                request, orgId, SCOPE_TYPE_ORGANIZATION, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 組織スケジュール詳細を取得する。
     */
    @GetMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> getSchedule(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId) {
        scheduleService.getScheduleWithAccessCheck(scheduleId, SecurityUtils.getCurrentUserId());
        // TODO: ScheduleDetailResponse への変換はMapper実装後に対応
        List<ScheduleResponse> list = scheduleService.listOrgSchedules(orgId,
                LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59));
        ScheduleResponse found = list.stream()
                .filter(s -> s.getId().equals(scheduleId))
                .findFirst()
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(found));
    }

    /**
     * 組織スケジュールを更新する。
     */
    @PatchMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> updateSchedule(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        ScheduleResponse response = scheduleService.updateSchedule(
                scheduleId, request, updateScope, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "組織スケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "THIS_ONLY") String updateScope) {
        scheduleService.deleteSchedule(scheduleId, updateScope);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織スケジュールをキャンセルする。
     */
    @PostMapping("/{scheduleId}/cancel")
    @Operation(summary = "組織スケジュールキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelSchedule(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId) {
        scheduleService.cancelSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織スケジュールの出欠集計を取得する。
     */
    @GetMapping("/{scheduleId}/attendances")
    @Operation(summary = "組織出欠集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> getAttendances(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId) {
        List<AttendanceResponse> responses = attendanceService.getAttendances(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 組織スケジュールの出欠一覧をCSVエクスポートする。
     */
    @GetMapping("/{scheduleId}/attendances/export")
    @Operation(summary = "組織出欠CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<byte[]> exportAttendancesCsv(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId) {
        String csv = attendanceService.exportAttendancesCsv(scheduleId);
        byte[] csvBytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendances_" + scheduleId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvBytes);
    }

    /**
     * 組織スケジュールを複製する。
     */
    @PostMapping("/{scheduleId}/duplicate")
    @Operation(summary = "組織スケジュール複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> duplicateSchedule(
            @PathVariable Long orgId,
            @PathVariable Long scheduleId) {
        ScheduleResponse response = scheduleService.duplicateSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
