package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.MemberPerformanceResponse;
import com.mannschaft.app.performance.dto.SchedulePerformanceResponse;
import com.mannschaft.app.performance.dto.TeamStatsResponse;
import com.mannschaft.app.performance.service.PerformanceStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * パフォーマンス統計コントローラー。チーム統計・メンバー統計・スケジュール/活動記録紐付き一覧APIを提供する。
 */
@RestController
@Tag(name = "パフォーマンス統計", description = "F07.2 パフォーマンス統計ダッシュボード・メンバー統計")
@RequiredArgsConstructor
public class PerformanceStatsController {

    private final PerformanceStatsService statsService;

    /**
     * チーム統計ダッシュボードを取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/performance/stats")
    @Operation(summary = "チーム統計ダッシュボード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamStatsResponse>> getTeamStats(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        TeamStatsResponse response = statsService.getTeamStats(teamId, metricId, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 特定メンバーのパフォーマンスを取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/members/{userId}/performance")
    @Operation(summary = "メンバーパフォーマンス")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MemberPerformanceResponse>> getMemberPerformance(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        MemberPerformanceResponse response = statsService.getMemberPerformance(teamId, userId, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スケジュール紐付きパフォーマンス一覧を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance")
    @Operation(summary = "スケジュール紐付きパフォーマンス一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SchedulePerformanceResponse>> getSchedulePerformance(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId) {
        SchedulePerformanceResponse response = statsService.getSchedulePerformance(teamId, scheduleId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 活動記録紐付きパフォーマンス一覧を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/activities/{activityId}/performance")
    @Operation(summary = "活動記録紐付きパフォーマンス一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SchedulePerformanceResponse>> getActivityPerformance(
            @PathVariable Long teamId,
            @PathVariable Long activityId) {
        SchedulePerformanceResponse response = statsService.getActivityPerformance(teamId, activityId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
