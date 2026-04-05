package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * シフトスケジュールコントローラー。シフトスケジュールのCRUD・ステータス遷移APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/schedules")
@Tag(name = "シフトスケジュール管理", description = "F03.5 シフトスケジュールのCRUD・ステータス遷移")
@RequiredArgsConstructor
public class ShiftScheduleController {

    private final ShiftScheduleService scheduleService;


    /**
     * チームのシフトスケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "シフトスケジュール一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShiftScheduleResponse>>> listSchedules(
            @RequestParam Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<ShiftScheduleResponse> responses;
        if (from != null && to != null) {
            responses = scheduleService.listSchedulesByPeriod(teamId, from, to);
        } else {
            responses = scheduleService.listSchedules(teamId);
        }
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * シフトスケジュール詳細を取得する。
     */
    @GetMapping("/{scheduleId}")
    @Operation(summary = "シフトスケジュール詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ShiftScheduleResponse>> getSchedule(
            @PathVariable Long scheduleId) {
        ShiftScheduleResponse response = scheduleService.getSchedule(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * シフトスケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "シフトスケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ShiftScheduleResponse>> createSchedule(
            @RequestParam Long teamId,
            @Valid @RequestBody CreateShiftScheduleRequest request) {
        ShiftScheduleResponse response = scheduleService.createSchedule(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * シフトスケジュールを更新する。
     */
    @PatchMapping("/{scheduleId}")
    @Operation(summary = "シフトスケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShiftScheduleResponse>> updateSchedule(
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateShiftScheduleRequest request) {
        ShiftScheduleResponse response = scheduleService.updateSchedule(scheduleId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * シフトスケジュールを削除する（論理削除）。
     */
    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "シフトスケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long scheduleId) {
        scheduleService.deleteSchedule(scheduleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * シフトスケジュールのステータスを遷移する。
     */
    @PostMapping("/{scheduleId}/transition")
    @Operation(summary = "シフトスケジュールステータス遷移")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "遷移成功")
    public ResponseEntity<ApiResponse<ShiftScheduleResponse>> transitionStatus(
            @PathVariable Long scheduleId,
            @RequestParam String status) {
        ShiftScheduleResponse response = scheduleService.transitionStatus(scheduleId, status, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * シフトスケジュールを複製する。
     */
    @PostMapping("/{scheduleId}/duplicate")
    @Operation(summary = "シフトスケジュール複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ShiftScheduleResponse>> duplicateSchedule(
            @PathVariable Long scheduleId) {
        ShiftScheduleResponse response = scheduleService.duplicateSchedule(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
