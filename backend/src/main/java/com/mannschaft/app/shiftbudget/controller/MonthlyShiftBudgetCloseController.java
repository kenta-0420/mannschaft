package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseRequest;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseResponse;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

/**
 * F08.7 シフト予算 月次締め コントローラー（Phase 9-δ 第2段 / API #11）。
 *
 * <p>設計書 F08.7 (v1.3) §6.1 #11 / §4.6 / §13 Phase 10-β に準拠。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>{@code POST /api/v1/shift-budget/monthly-close} — 月次締めの手動起動 (BUDGET_ADMIN)</li>
 * </ul>
 *
 * <p>cron 自動起動は {@link com.mannschaft.app.shiftbudget.batch.MonthlyShiftBudgetCloseBatchJob}
 * （application.yml の {@code feature.shift-budget.monthly-close-cron-enabled=true} で有効化）。</p>
 *
 * <p>Phase 10-β 拡張: レスポンス DTO に {@code processed_organization_ids} /
 * {@code failed_organization_ids} / {@code already_closed_organization_ids} を含める
 * （単一組織 close では {@code processed_organization_ids} のみセット、他は空配列）。</p>
 */
@RestController
@RequestMapping("/api/v1/shift-budget/monthly-close")
@Tag(name = "シフト予算 月次締め (F08.7)",
     description = "Phase 9-δ 第2段: 月次締めバッチの手動起動 API (BUDGET_ADMIN 必須)")
@RequiredArgsConstructor
public class MonthlyShiftBudgetCloseController {

    private final MonthlyShiftBudgetCloseService closeService;

    @PostMapping
    @Operation(summary = "月次締めバッチを手動起動 (PLANNED→CONFIRMED 遷移 + F08.6 仕訳生成)")
    public ApiResponse<MonthlyCloseResponse> close(
            @Valid @RequestBody MonthlyCloseRequest request) {

        // バリデーション通過済 (Pattern で形式チェック済) のため例外発生しない
        YearMonth targetMonth = YearMonth.parse(request.yearMonth());

        MonthlyShiftBudgetCloseService.CloseResult result =
                closeService.close(request.organizationId(), targetMonth);

        // Phase 10-β: 単一組織 close も新形式の DTO で返す（処理組織 ID のみ含む）
        return ApiResponse.of(MonthlyCloseResponse.builder()
                .organizationId(request.organizationId())
                .yearMonth(request.yearMonth())
                .closedAllocations(result.closedAllocations())
                .alreadyClosedAllocations(result.alreadyClosedAllocations())
                .closedConsumptions(result.closedConsumptions())
                .processedOrganizationIds(
                        result.closedAllocations() > 0
                                ? List.of(request.organizationId())
                                : List.of())
                .failedOrganizationIds(List.of())
                .alreadyClosedOrganizationIds(
                        result.alreadyClosedAllocations() > 0
                                ? List.of(request.organizationId())
                                : List.of())
                .build());
    }
}
