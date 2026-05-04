package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetCalcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.7 シフト予算逆算 API コントローラー (Phase 9-α)。
 *
 * <p>API 一覧:</p>
 * <ul>
 *   <li>{@code POST /api/v1/shift-budget/calc/required-slots} — 予算→必要シフト枠数の逆算</li>
 * </ul>
 *
 * <p>本 Controller はステートレス計算のみ提供し、DB 書き込みを伴わない。
 * 設計書 F08.7 §6.1 / §6.2.2 / §13 段階導入計画 Phase 9-α に準拠。</p>
 *
 * <p>必要権限: {@code MANAGE_SHIFTS} (TEAM スコープ)</p>
 */
@RestController
@RequestMapping("/api/v1/shift-budget")
@Tag(name = "シフト予算 (F08.7)",
     description = "Phase 9-α: 予算→必要シフト枠数の逆算 API (ステートレス)")
@RequiredArgsConstructor
public class ShiftBudgetCalcController {

    private final ShiftBudgetCalcService calcService;

    /**
     * 予算額から必要シフト枠数を逆算する。
     *
     * <p>設計書 F08.7 §4.1 数式: {@code 必要枠数 = floor(予算 / (平均時給 × スロット時間))}</p>
     *
     * <p>3 モード:</p>
     * <ul>
     *   <li>{@code MEMBER_AVG} — チーム全アクティブメンバーの単純平均</li>
     *   <li>{@code POSITION_AVG} — ポジション別 × 必要人数で加重平均</li>
     *   <li>{@code EXPLICIT} — 呼び出し側指定の {@code avg_hourly_rate} を採用</li>
     * </ul>
     *
     * <p>境界ケース warning: {@code BUDGET_ZERO}, {@code AVG_RATE_ZERO},
     * {@code INSUFFICIENT_RATE_DATA}, {@code POSITION_NO_RATE_DATA}</p>
     *
     * <p>エラー:</p>
     * <ul>
     *   <li>400: {@code EMPTY_POSITION_LIST}, {@code DUPLICATE_POSITION_ID},
     *            {@code INVALID_REQUIRED_COUNT}, {@code INVALID_SLOT_HOURS} 等</li>
     *   <li>403: {@code MANAGE_SHIFTS} 権限なし</li>
     *   <li>404: 指定 team_id が存在しない / 組織スコープ不一致 (IDOR 対策)</li>
     *   <li>503: フィーチャーフラグ {@code feature.shift-budget.enabled = false}</li>
     * </ul>
     *
     * @param request 逆算リクエスト
     * @return 必要枠数・平均時給・計算式・警告を含むレスポンス
     */
    @PostMapping("/calc/required-slots")
    @Operation(summary = "シフト枠数を予算から逆算 (Phase 9-α)",
               description = "ステートレス計算 API。MEMBER_AVG/POSITION_AVG/EXPLICIT の 3 モード対応。")
    public ResponseEntity<ApiResponse<RequiredSlotsResponse>> calculateRequiredSlots(
            @Valid @RequestBody RequiredSlotsRequest request) {
        RequiredSlotsResponse response = calcService.calculateRequiredSlots(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
