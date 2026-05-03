package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.school.dto.BatchRunResponse;
import com.mannschaft.app.school.service.AttendanceRequirementBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * F03.13 Phase 14: 出席要件評価バッチの手動実行 API（ADMIN限定）。
 *
 * <p>通常はスケジュールで自動実行されるが、手動トリガーが必要な場合に使用する。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/batch/attendance")
@RequiredArgsConstructor
@Tag(name = "Attendance Batch", description = "出席要件評価バッチの手動実行 API（ADMIN限定）")
public class AttendanceBatchController {

    private final AttendanceRequirementBatchService batchService;

    /**
     * 日次評価バッチを手動実行する。
     *
     * @return バッチ実行結果レスポンス
     */
    @Operation(summary = "日次評価バッチを手動実行")
    @PostMapping("/run-daily-evaluation")
    public ResponseEntity<ApiResponse<BatchRunResponse>> runDailyEvaluation() {
        batchService.runDailyEvaluation();
        BatchRunResponse res = new BatchRunResponse(
                "runDailyEvaluation", 0,
                LocalDateTime.now().toString());
        return ResponseEntity.ok(ApiResponse.of(res));
    }

    /**
     * 週次ダイジェストを手動送信する。
     *
     * @return バッチ実行結果レスポンス
     */
    @Operation(summary = "週次ダイジェストを手動送信")
    @PostMapping("/send-weekly-digest")
    public ResponseEntity<ApiResponse<BatchRunResponse>> sendWeeklyDigest() {
        batchService.sendWeeklyDigest();
        BatchRunResponse res = new BatchRunResponse(
                "sendWeeklyDigest", 0,
                LocalDateTime.now().toString());
        return ResponseEntity.ok(ApiResponse.of(res));
    }
}
