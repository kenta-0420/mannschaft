package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.PeriodAttendanceListResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceSummary;
import com.mannschaft.app.school.dto.PeriodAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.PeriodCandidatesResponse;
import com.mannschaft.app.school.dto.StudentTimelineResponse;
import com.mannschaft.app.school.service.PeriodAttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * F03.13 学校出欠管理: 時限別出欠エンドポイント。
 *
 * <p>設計書 §5.2 の5エンドポイントを提供する:</p>
 * <ul>
 *   <li>GET  /teams/{teamId}/attendance/periods — 特定日の時限別出欠一覧</li>
 *   <li>GET  /teams/{teamId}/attendance/periods/{periodNumber}/candidates — 対象生徒一覧</li>
 *   <li>POST /teams/{teamId}/attendance/periods/{periodNumber} — 一括登録</li>
 *   <li>PATCH /teams/{teamId}/attendance/periods/{recordId} — 個別修正</li>
 *   <li>GET  /me/attendance/timeline — 生徒の1日タイムライン</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class PeriodAttendanceController {

    private final PeriodAttendanceService periodAttendanceService;

    /**
     * 特定日の時限別出欠一覧を取得する。
     */
    @GetMapping("/teams/{teamId}/attendance/periods")
    @Operation(summary = "時限別出欠一覧取得")
    public ApiResponse<PeriodAttendanceListResponse> getPeriodAttendance(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Integer periodNumber) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(periodAttendanceService.getPeriodAttendance(teamId, date, periodNumber, currentUserId));
    }

    /**
     * 時限の対象生徒一覧と直前時限ステータスを取得する。
     */
    @GetMapping("/teams/{teamId}/attendance/periods/{periodNumber}/candidates")
    @Operation(summary = "時限対象生徒一覧取得")
    public ApiResponse<PeriodCandidatesResponse> getPeriodCandidates(
            @PathVariable Long teamId,
            @PathVariable Integer periodNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(periodAttendanceService.getPeriodCandidates(teamId, date, periodNumber, currentUserId));
    }

    /**
     * 時限出欠を一括登録する（教科担任用）。
     */
    @PostMapping("/teams/{teamId}/attendance/periods/{periodNumber}")
    @Operation(summary = "時限出欠一括登録")
    public ResponseEntity<ApiResponse<PeriodAttendanceSummary>> submitPeriodAttendance(
            @PathVariable Long teamId,
            @PathVariable Integer periodNumber,
            @Valid @RequestBody PeriodAttendanceRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        PeriodAttendanceSummary summary =
                periodAttendanceService.submitPeriodAttendance(teamId, periodNumber, request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(summary));
    }

    /**
     * 時限出欠レコードを個別修正する。
     */
    @PatchMapping("/teams/{teamId}/attendance/periods/{recordId}")
    @Operation(summary = "時限出欠個別修正")
    public ApiResponse<PeriodAttendanceResponse> updatePeriodRecord(
            @PathVariable Long teamId,
            @PathVariable Long recordId,
            @Valid @RequestBody PeriodAttendanceUpdateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(periodAttendanceService.updatePeriodRecord(teamId, recordId, request, currentUserId));
    }

    /**
     * 生徒の1日タイムラインを取得する。
     */
    @GetMapping("/me/attendance/timeline")
    @Operation(summary = "生徒1日タイムライン取得")
    public ApiResponse<StudentTimelineResponse> getStudentDailyTimeline(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(periodAttendanceService.getStudentDailyTimeline(currentUserId, date, currentUserId));
    }
}
