package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.dto.AlertAcknowledgeRequest;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.service.BudgetThresholdAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F08.7 シフト予算 閾値超過警告 コントローラー（Phase 9-δ 第2段 / API #9 / #10）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #9-#10 / §6.2.5 に準拠。</p>
 *
 * <p>API:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/shift-budget/alerts}                      — 未承認警告一覧 (BUDGET_VIEW)</li>
 *   <li>{@code POST /api/v1/shift-budget/alerts/{id}/acknowledge}      — 承認応答 (BUDGET_ADMIN)</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 */
@RestController
@RequestMapping("/api/v1/shift-budget/alerts")
@Tag(name = "シフト予算 警告 (F08.7)",
     description = "Phase 9-δ 第2段: 閾値超過警告 (80/100/120%) 一覧 + 承認応答 API")
@RequiredArgsConstructor
public class BudgetThresholdAlertController {

    private final BudgetThresholdAlertService alertService;

    @GetMapping
    @Operation(summary = "未承認の閾値超過警告一覧を取得 (新しい警告ほど上)")
    public ApiResponse<List<AlertResponse>> listAlerts(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.of(alertService.list(organizationId, page, size));
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "閾値超過警告に対して承認応答する (BUDGET_ADMIN 必須)")
    public ApiResponse<AlertResponse> acknowledgeAlert(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertAcknowledgeRequest request) {
        String comment = request != null ? request.comment() : null;
        return ApiResponse.of(alertService.acknowledge(organizationId, id, comment));
    }
}
