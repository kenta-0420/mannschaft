package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseRequest;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseResponse;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseFacade;
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
 * F08.7 シフト予算 月次（期間）締め コントローラー（Phase 9-δ 第2段 / API #11。CMP-260909-2152 で期間締めに作り替え）。
 *
 * <p>設計書 F08.7 §6.1 #11 / §4.6 / §13 Phase 10-β に準拠。</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/shift-budget/monthly-close} — 期間締めの手動起動（組織 BUDGET_ADMIN のみ。
 *       SYSTEM_ADMIN 単独・BUDGET_VIEW のみ・他組織は 403）</li>
 * </ul>
 *
 * <p>{@code year_month} の形式は不変で、意味は「期間キー（締め日が属する月）」。応答に {@code period_start}・{@code period_end}・
 * {@code closing_day} を追加した。未終了の期間は 409 SHIFT_BUDGET_036（終了日当日以降は可）。</p>
 *
 * <p>cron 自動起動は {@link com.mannschaft.app.shiftbudget.batch.MonthlyShiftBudgetCloseBatchJob}
 * （application.yml の {@code feature.shift-budget.monthly-close-cron-enabled=true} で有効化。既定は無効）。</p>
 */
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget/monthly-close")
@Tag(name = "シフト予算 月次締め (F08.7)",
     description = "期間締めの手動起動 API (組織 BUDGET_ADMIN 必須。SYSTEM_ADMIN 単独は 403)")
@RequiredArgsConstructor
public class MonthlyShiftBudgetCloseController {

    private final MonthlyShiftBudgetCloseFacade closeFacade;

    @PostMapping
    @Operation(summary = "期間締めを手動起動 (PLANNED→CONFIRMED 遷移 + F08.6 仕訳生成)")
    public ApiResponse<MonthlyCloseResponse> close(
            @Valid @RequestBody MonthlyCloseRequest request) {

        // バリデーション通過済 (Pattern で形式チェック済) のため例外発生しない
        YearMonth periodKey = YearMonth.parse(request.yearMonth());

        MonthlyShiftBudgetCloseFacade.CloseOutcome outcome = closeFacade.close(request.organizationId(), periodKey);
        MonthlyShiftBudgetCloseCoordinator.CloseResult result = outcome.result();

        return ApiResponse.of(MonthlyCloseResponse.builder()
                .organizationId(request.organizationId())
                .yearMonth(request.yearMonth())
                .periodStart(outcome.period().start())
                .periodEnd(outcome.period().end())
                .closingDay(outcome.period().closingDay())
                .closedAllocations(result.closedAllocations())
                .alreadyClosedAllocations(result.alreadyClosedAllocations())
                .failedAllocations(result.failedAllocations())
                .closedConsumptions(result.closedConsumptions())
                .processedOrganizationIds(
                        result.closedAllocations() > 0
                                ? List.of(request.organizationId())
                                : List.of())
                .failedOrganizationIds(
                        result.failedAllocations() > 0
                                ? List.of(request.organizationId())
                                : List.of())
                .alreadyClosedOrganizationIds(
                        result.alreadyClosedAllocations() > 0
                                ? List.of(request.organizationId())
                                : List.of())
                .build());
    }
}
