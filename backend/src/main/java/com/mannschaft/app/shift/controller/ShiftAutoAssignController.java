package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.AssignmentRunResponse;
import com.mannschaft.app.shift.dto.AutoAssignRequest;
import com.mannschaft.app.shift.dto.ConfirmAutoAssignRequest;
import com.mannschaft.app.shift.dto.VisualReviewConfirmRequest;
import com.mannschaft.app.shift.service.ShiftAutoAssignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * シフト自動割当コントローラー。自動割当の実行・確定・破棄・履歴取得 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "シフト自動割当", description = "F03.5 シフト自動割当の実行・確定・破棄・履歴管理")
@RequiredArgsConstructor
public class ShiftAutoAssignController {

    private final ShiftAutoAssignService autoAssignService;

    /**
     * 自動割当を実行する。
     */
    @PostMapping("/schedules/{scheduleId}/auto-assign")
    @Operation(summary = "自動割当実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "割当実行成功")
    public ResponseEntity<ApiResponse<AssignmentRunResponse>> runAutoAssign(
            @PathVariable Long scheduleId,
            @Valid @RequestBody AutoAssignRequest request) {
        AssignmentRunResponse response = autoAssignService.runAutoAssign(
                scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自動割当提案を確定する。
     */
    @PostMapping("/schedules/{scheduleId}/auto-assign/confirm")
    @Operation(summary = "自動割当提案確定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確定成功")
    public ResponseEntity<Void> confirmAutoAssign(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ConfirmAutoAssignRequest request) {
        autoAssignService.confirmAutoAssign(scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * 自動割当提案を破棄する。
     */
    @DeleteMapping("/schedules/{scheduleId}/auto-assign")
    @Operation(summary = "自動割当提案破棄")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "破棄成功")
    public ResponseEntity<Void> revokeAutoAssign(
            @PathVariable Long scheduleId,
            @RequestBody Long runId) {
        autoAssignService.revokeAutoAssign(scheduleId, runId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 自動割当実行履歴一覧を取得する。
     */
    @GetMapping("/schedules/{scheduleId}/assignment-runs")
    @Operation(summary = "自動割当実行履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AssignmentRunResponse>>> getAssignmentRuns(
            @PathVariable Long scheduleId) {
        List<AssignmentRunResponse> responses = autoAssignService.getAssignmentRuns(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 自動割当実行ログ詳細を取得する（割当提案一覧を含む）。
     */
    @GetMapping("/assignment-runs/{runId}")
    @Operation(summary = "自動割当実行ログ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AssignmentRunResponse>> getAssignmentRunDetail(
            @PathVariable Long runId) {
        AssignmentRunResponse response = autoAssignService.getAssignmentRunDetail(runId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 目視確認を完了させる。
     */
    @PostMapping("/assignment-runs/{runId}/confirm-visual-review")
    @Operation(summary = "目視確認完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認完了")
    public ResponseEntity<Void> confirmVisualReview(
            @PathVariable Long runId,
            @RequestBody(required = false) VisualReviewConfirmRequest request) {
        String note = request != null ? request.note() : null;
        autoAssignService.confirmVisualReview(runId, note, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }
}
