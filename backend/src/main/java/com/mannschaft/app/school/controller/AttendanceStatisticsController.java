package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.MonthlyStatisticsResponse;
import com.mannschaft.app.school.dto.StudentTermStatisticsResponse;
import com.mannschaft.app.school.service.AttendanceStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** F03.13 学校出欠: 出欠統計・CSV エクスポートエンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceStatisticsController {

    private final AttendanceStatisticsService statisticsService;

    /**
     * 担任向け月次出欠集計を取得する。
     *
     * @param teamId クラスチームID
     * @param year   対象年
     * @param month  対象月（1〜12）
     * @return 月次集計レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/statistics/monthly")
    @Operation(summary = "月次出欠集計取得", description = "担任が指定月のクラス全員の出欠集計を取得する。")
    public ApiResponse<MonthlyStatisticsResponse> getMonthlyStatistics(
            @PathVariable Long teamId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ApiResponse.of(statisticsService.getMonthlyStatistics(teamId, year, month));
    }

    /**
     * 生徒・保護者向け期間別出欠集計を取得する。
     *
     * @param teamId クラスチームID
     * @param from   集計開始日（YYYY-MM-DD）
     * @param to     集計終了日（YYYY-MM-DD）
     * @return 期間別集計レスポンス
     */
    @GetMapping("/me/attendance/statistics/term")
    @Operation(summary = "期間別出欠集計取得", description = "生徒本人が指定期間の自分の出欠集計と教科別出席率を取得する。")
    public ApiResponse<StudentTermStatisticsResponse> getTermStatistics(
            @RequestParam Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(statisticsService.getStudentTermStatistics(currentUserId, teamId, from, to));
    }

    /**
     * 担任向け出欠 CSV エクスポート。
     *
     * @param teamId クラスチームID
     * @param from   開始日（YYYY-MM-DD）
     * @param to     終了日（YYYY-MM-DD）
     * @return CSV ファイル（Content-Disposition: attachment）
     */
    @GetMapping("/teams/{teamId}/attendance/export")
    @Operation(summary = "出欠 CSV エクスポート", description = "担任が指定期間のクラス出欠データを CSV 形式でエクスポートする。")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] data = statisticsService.exportAttendanceCsv(teamId, from, to);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition",
                        "attachment; filename=\"attendance_" + from + "_" + to + ".csv\"")
                .body(data);
    }
}
