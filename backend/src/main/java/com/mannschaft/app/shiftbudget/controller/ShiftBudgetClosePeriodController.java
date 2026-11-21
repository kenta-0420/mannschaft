package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shiftbudget.dto.ClosePeriodListResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetBusinessDateProvider;
import com.mannschaft.app.shiftbudget.dto.UncountedShiftListResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 締め期間一覧・未計上勤務のコントローラー（CMP-260909-2152）。すべて組織 BUDGET_VIEW。
 *
 * <ul>
 *   <li>{@code GET /api/v1/shift-budget/close-periods?from=YYYY-MM&to=YYYY-MM} — 組織の締め期間一覧（36か月まで）</li>
 *   <li>{@code GET /api/v1/shift-budget/allocations/{id}/close-periods} — 割当と重なる締め期間一覧</li>
 *   <li>{@code GET /api/v1/shift-budget/allocations/{id}/monthly-closes/{period}/uncounted-shifts} — 締め済み期間の未計上勤務</li>
 * </ul>
 */
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget")
@Tag(name = "シフト予算 締め期間 (F08.7)", description = "組織の締め期間一覧と未計上勤務の参照")
@RequiredArgsConstructor
public class ShiftBudgetClosePeriodController {

    private final ShiftBudgetClosePeriodFacade facade;
    private final ShiftBudgetBusinessDateProvider dateProvider;

    @GetMapping("/close-periods")
    @Operation(summary = "組織の締め期間一覧（期間キー from〜to。from>to または36か月超は 400）")
    public ApiResponse<ClosePeriodListResponse> listByOrganization(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        return ApiResponse.of(ClosePeriodListResponse.from(
                facade.listByOrganization(organizationId, parseKey(from), parseKey(to)), dateProvider.today()));
    }

    @GetMapping("/allocations/{id}/close-periods")
    @Operation(summary = "割当と重なる締め期間の一覧")
    public ApiResponse<ClosePeriodListResponse> listByAllocation(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long allocationId) {
        return ApiResponse.of(ClosePeriodListResponse.from(facade.listByAllocation(organizationId, allocationId),
                dateProvider.today()));
    }

    @GetMapping("/allocations/{id}/monthly-closes/{period}/uncounted-shifts")
    @Operation(summary = "締め済み期間の未計上勤務（未締めの期間キーは 409）")
    public ApiResponse<UncountedShiftListResponse> uncountedShifts(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long allocationId,
            @PathVariable("period") String period) {
        return ApiResponse.of(UncountedShiftListResponse.from(
                facade.uncountedShifts(organizationId, allocationId, parseKey(period))));
    }

    private static YearMonth parseKey(String key) {
        try {
            return YearMonth.parse(key);
        } catch (DateTimeParseException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
