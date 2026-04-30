package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.AttendanceHistoryItem;
import com.mannschaft.app.school.dto.DailyAttendanceListResponse;
import com.mannschaft.app.school.dto.DailyAttendanceResponse;
import com.mannschaft.app.school.dto.DailyAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.DailyRollCallSummary;
import com.mannschaft.app.school.service.DailyAttendanceService;
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
import java.util.List;

/** F03.13 学校出欠: 日次出欠エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class DailyAttendanceController {

    private final DailyAttendanceService dailyAttendanceService;

    /**
     * 特定日のクラス日次出欠一覧を取得する。
     *
     * @param teamId クラスチームID
     * @param date   対象日（YYYY-MM-DD）
     * @return 日次出欠一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/daily")
    @Operation(summary = "日次出欠一覧取得", description = "指定日のクラス全員の日次出欠一覧を取得する。担任・教科担任・生徒本人・保護者が参照可能。")
    public ApiResponse<DailyAttendanceListResponse> getDailyAttendance(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(dailyAttendanceService.getDailyAttendance(teamId, date, currentUserId));
    }

    /**
     * 朝の点呼を一括登録する。
     *
     * @param teamId  クラスチームID
     * @param request 点呼一括登録リクエスト
     * @return 点呼登録結果サマリ（201 Created）
     */
    @PostMapping("/teams/{teamId}/attendance/daily/roll-call")
    @Operation(summary = "朝の点呼一括登録", description = "担任がクラス全員の出欠を一括登録する。upsert（既存あれば更新、なければ新規作成）。")
    public ResponseEntity<ApiResponse<DailyRollCallSummary>> submitDailyRollCall(
            @PathVariable Long teamId,
            @Valid @RequestBody DailyRollCallRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        DailyRollCallSummary summary = dailyAttendanceService.submitDailyRollCall(teamId, request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(summary));
    }

    /**
     * 日次出欠レコードを個別修正する。
     *
     * @param teamId   クラスチームID
     * @param recordId 対象レコードID
     * @param request  修正リクエスト（null フィールドは変更しない）
     * @return 更新後の日次出欠レスポンス
     */
    @PatchMapping("/teams/{teamId}/attendance/daily/{recordId}")
    @Operation(summary = "日次出欠個別修正", description = "担任が日次出欠レコードを個別修正する。null フィールドは変更しない（部分更新）。")
    public ApiResponse<DailyAttendanceResponse> updateDailyRecord(
            @PathVariable Long teamId,
            @PathVariable Long recordId,
            @Valid @RequestBody DailyAttendanceUpdateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(dailyAttendanceService.updateDailyRecord(teamId, recordId, request, currentUserId));
    }

    /**
     * 自分（または子供）の日次出欠履歴を取得する。
     *
     * @param from 開始日（YYYY-MM-DD）
     * @param to   終了日（YYYY-MM-DD）
     * @return 日次出欠履歴アイテム一覧
     */
    @GetMapping("/me/attendance/daily")
    @Operation(summary = "自分の日次出欠履歴取得", description = "生徒本人が自分の日次出欠履歴を取得する。期間指定で絞り込み可能。")
    public ApiResponse<List<AttendanceHistoryItem>> getMyDailyAttendanceHistory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(dailyAttendanceService.getStudentHistory(currentUserId, from, to, currentUserId));
    }
}
