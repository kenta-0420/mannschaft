package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shiftbudget.dto.ClosingDayChangeRequest;
import com.mannschaft.app.shiftbudget.dto.ClosingSettingResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosingSettingFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/**
 * 組織の締め日設定コントローラー（CMP-260909-2152）。
 *
 * <ul>
 *   <li>{@code GET  /api/v1/shift-budget/closing-settings} — 参照（組織 BUDGET_VIEW）</li>
 *   <li>{@code POST /api/v1/shift-budget/closing-settings/changes} — 変更（組織 BUDGET_ADMIN。理由必須・履歴は追記のみ）</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを指定する。SYSTEM_ADMIN 単独・他組織は 403。履歴の更新・削除の経路は無い。</p>
 */
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget/closing-settings")
@Tag(name = "シフト予算 締め日設定 (F08.7)", description = "組織ごとの締め日（1〜28・0＝末日）の参照と変更")
@RequiredArgsConstructor
public class ShiftBudgetClosingSettingController {

    private final ShiftBudgetClosingSettingFacade facade;

    @GetMapping
    @Operation(summary = "締め日設定を取得（設定なしは末日・revision 0）")
    public ApiResponse<ClosingSettingResponse> get(@RequestHeader("X-Organization-Id") Long organizationId) {
        return ApiResponse.of(ClosingSettingResponse.from(facade.get(organizationId)));
    }

    @PostMapping("/changes")
    @Operation(summary = "締め日を変更（適用開始は今日を含む期間・最後の締めより後。移行期間を許す）")
    public ApiResponse<ClosingSettingResponse> change(@RequestHeader("X-Organization-Id") Long organizationId,
                                                      @Valid @RequestBody ClosingDayChangeRequest request) {
        int closingDay = request.requireClosingDay();
        return ApiResponse.of(ClosingSettingResponse.from(facade.change(organizationId, request.expectedRevision(),
                closingDay, YearMonth.parse(request.effectivePeriodKey()), request.reason())));
    }
}
