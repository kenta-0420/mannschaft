package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.BatchRunResponse;
import com.mannschaft.app.school.service.AttendanceRequirementBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * F03.13 Phase 14: 出席要件評価バッチの手動実行 API（SYSTEM_ADMIN 限定）。
 *
 * <p>通常はスケジュールで自動実行されるが、手動トリガーが必要な場合に使用する。</p>
 *
 * <p><b>認可の真の強制点（Track2 第二陣 / 2026-05-29）</b>: クラス注釈の
 * {@code @PreAuthorize("hasRole('ADMIN')")} は {@code hasRole} である以上 per-scope 判定にならない。
 * 加えて、本バッチは特定チーム・組織に閉じず
 * 全 ACTIVE 規程を横断評価するプラットフォーム全体の運用操作であり、
 * 単一スコープの ADMIN に開放すると他テナントへの越境操作（クロステナント昇格）になる。
 * よって各エンドポイントで {@link AccessControlService#checkSystemAdmin(Long)} により
 * SYSTEM_ADMIN を強制する。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/batch/attendance")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "Attendance Batch", description = "出席要件評価バッチの手動実行 API（SYSTEM_ADMIN 限定）")
public class AttendanceBatchController {

    private final AttendanceRequirementBatchService batchService;
    private final AccessControlService accessControlService;

    /**
     * 日次評価バッチを手動実行する。
     *
     * @return バッチ実行結果レスポンス
     */
    @Operation(summary = "日次評価バッチを手動実行")
    @PostMapping("/run-daily-evaluation")
    public ResponseEntity<ApiResponse<BatchRunResponse>> runDailyEvaluation() {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
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
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        batchService.sendWeeklyDigest();
        BatchRunResponse res = new BatchRunResponse(
                "sendWeeklyDigest", 0,
                LocalDateTime.now().toString());
        return ResponseEntity.ok(ApiResponse.of(res));
    }
}
