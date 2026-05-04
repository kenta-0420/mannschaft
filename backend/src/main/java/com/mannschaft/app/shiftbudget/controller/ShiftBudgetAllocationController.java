package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.AllocationListResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetAllocationService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.7 シフト予算割当 コントローラー（Phase 9-β / API #1〜#5）。
 *
 * <p>API 一覧:</p>
 * <ul>
 *   <li>{@code GET    /api/v1/shift-budget/allocations} — 一覧</li>
 *   <li>{@code POST   /api/v1/shift-budget/allocations} — 作成</li>
 *   <li>{@code GET    /api/v1/shift-budget/allocations/{id}} — 詳細</li>
 *   <li>{@code PUT    /api/v1/shift-budget/allocations/{id}} — 更新（楽観ロック）</li>
 *   <li>{@code DELETE /api/v1/shift-budget/allocations/{id}} — 論理削除</li>
 *   <li>{@code GET    /api/v1/shift-budget/allocations/{id}/consumption-summary} — 消化サマリ</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>一覧/詳細/サマリ: {@code BUDGET_VIEW}</li>
 *   <li>作成/更新/削除: {@code BUDGET_MANAGE}</li>
 * </ul>
 *
 * <p>TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時、
 * {@code consumption-summary} に {@code @JsonView(BudgetView.BudgetAdmin.class)} を付与し、
 * Service 側で {@code by_user} に個人別内訳を返すよう改修する。
 * Phase 9-β 中は {@code by_user} を常に空配列で返す方針 (Q2 御裁可) のため、
 * Controller では {@code @JsonView} を付与せず全フィールドを serialize する。</p>
 */
@RestController
@RequestMapping("/api/v1/shift-budget/allocations")
@Tag(name = "シフト予算割当 (F08.7)",
     description = "Phase 9-β: シフト予算割当 CRUD + 消化サマリ API")
@RequiredArgsConstructor
public class ShiftBudgetAllocationController {

    private final ShiftBudgetAllocationService allocationService;
    private final ShiftBudgetSummaryService summaryService;

    @GetMapping
    @Operation(summary = "シフト予算割当の一覧を取得")
    public ApiResponse<AllocationListResponse> listAllocations(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        AllocationListResponse response = allocationService.listAllocations(organizationId, page, size);
        return ApiResponse.of(response);
    }

    @PostMapping
    @Operation(summary = "シフト予算割当を作成")
    public ResponseEntity<ApiResponse<AllocationResponse>> createAllocation(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody AllocationCreateRequest request) {
        AllocationResponse response = allocationService.createAllocation(organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "シフト予算割当の詳細を取得")
    public ApiResponse<AllocationResponse> getAllocation(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        AllocationResponse response = allocationService.getAllocation(organizationId, id);
        return ApiResponse.of(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "シフト予算割当を更新（楽観ロック）")
    public ApiResponse<AllocationResponse> updateAllocation(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id,
            @Valid @RequestBody AllocationUpdateRequest request) {
        AllocationResponse response = allocationService.updateAllocation(organizationId, id, request);
        return ApiResponse.of(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "シフト予算割当を論理削除")
    public ResponseEntity<Void> deleteAllocation(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        allocationService.deleteAllocation(organizationId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/consumption-summary")
    @Operation(summary = "シフト予算消化サマリを取得")
    public ApiResponse<ConsumptionSummaryResponse> getConsumptionSummary(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        ConsumptionSummaryResponse response = summaryService.getConsumptionSummary(organizationId, id);
        return ApiResponse.of(response);
    }
}
