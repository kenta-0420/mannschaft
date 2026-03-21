package com.mannschaft.app.performance.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.performance.dto.BulkRecordResponse;
import com.mannschaft.app.performance.dto.ScheduleBulkRecordRequest;
import com.mannschaft.app.performance.service.PerformanceRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * スケジュール連携パフォーマンスコントローラー。
 * スケジュールからの一括記録入力APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance/records")
@Tag(name = "スケジュール連携パフォーマンス", description = "F07.2 スケジュールからの一括記録入力")
@RequiredArgsConstructor
public class PerformanceScheduleController {

    private final PerformanceRecordService recordService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * スケジュールからの一括記録入力する。
     */
    @PostMapping("/bulk")
    @Operation(summary = "スケジュールからの一括記録入力")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BulkRecordResponse>> createScheduleBulkRecords(
            @PathVariable Long teamId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleBulkRecordRequest request) {
        BulkRecordResponse response = recordService.createScheduleBulkRecords(
                teamId, scheduleId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
