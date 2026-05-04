package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.dto.FailedEventResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F08.7 Phase 10-β: 失敗イベント管理コントローラー。
 *
 * <p>9-δ AFTER_COMMIT hook の swallow パターンで握りつぶされたサイレント失敗を
 * 運用者が確認・再実行・補正済マークするための API。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/shift-budget/failed-events}              — 一覧 (BUDGET_VIEW)</li>
 *   <li>{@code POST /api/v1/shift-budget/failed-events/{id}/retry}   — 個別再実行 (BUDGET_ADMIN)</li>
 *   <li>{@code POST /api/v1/shift-budget/failed-events/{id}/resolve} — 手動補正済マーク (BUDGET_ADMIN)</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 */
@RestController
@RequestMapping("/api/v1/shift-budget/failed-events")
@Tag(name = "シフト予算 失敗イベント (F08.7)",
     description = "Phase 10-β: 通知失敗 / hook 失敗イベントの一覧・再実行・手動補正済マーク API")
@RequiredArgsConstructor
public class ShiftBudgetFailedEventController {

    private final ShiftBudgetFailedEventService failedEventService;

    @GetMapping
    @Operation(summary = "失敗イベント一覧を取得 (status で絞り込み可、新しい順)")
    public ApiResponse<List<FailedEventResponse>> list(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam(name = "status", required = false) ShiftBudgetFailedEventStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.of(failedEventService.list(organizationId, status, page, size));
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "失敗イベントを手動で再実行 (BUDGET_ADMIN 必須、EXHAUSTED にも適用可)")
    public ApiResponse<FailedEventResponse> retry(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        return ApiResponse.of(failedEventService.retry(organizationId, id));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "失敗イベントを手動補正済としてマーク (BUDGET_ADMIN 必須、終端ステータス遷移)")
    public ApiResponse<FailedEventResponse> resolve(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        return ApiResponse.of(failedEventService.markManualResolved(organizationId, id));
    }
}
