package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryService;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * F08.7 シフト予算 集計 (consumption-summary) 専用コントローラー（Phase 9-δ 第3段で新設）。
 *
 * <p>マスター御裁可 Q7 により {@link ShiftBudgetAllocationController} から責務分離。
 * API パスは互換維持: {@code GET /api/v1/shift-budget/allocations/{id}/consumption-summary}。</p>
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。
 * {@link BudgetView} 階層を {@link MappingJacksonValue} 経由で切り替えることで、
 * フィールド単位のマスキング（個人別時給の漏洩防止）を実現する。</p>
 *
 * <p>権限による View 解決:</p>
 * <ul>
 *   <li>{@code BUDGET_ADMIN} 保有 → {@link BudgetView.BudgetAdmin}（全フィールド公開、{@code by_user} 含む）</li>
 *   <li>{@code BUDGET_VIEW} のみ → {@link BudgetView.BudgetViewer}（{@code by_user} 除外、flags=BY_USER_HIDDEN）</li>
 *   <li>権限なし → Service 層が 403 を返すため到達しない</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shift-budget/allocations")
@Tag(name = "シフト予算 集計 (F08.7)",
     description = "Phase 9-δ 第3段: 消化サマリ API (BUDGET_ADMIN/VIEW で View 切替)")
@RequiredArgsConstructor
public class ShiftBudgetSummaryController {

    private final ShiftBudgetSummaryService summaryService;
    private final AccessControlService accessControlService;

    /**
     * 指定 allocation の消化サマリを返す。
     *
     * <p>{@link MappingJacksonValue} で {@link BudgetView} を切替えることでフィールド単位のマスキングを行う。
     * Service 層では {@code BUDGET_VIEW} を必須とし、{@code BUDGET_ADMIN} 保有時のみ {@code by_user} に
     * 実データを詰める。Controller 側では追加で View 階層を選択して JSON シリアライズ時に
     * {@code by_user} フィールドそのものを除外（{@code BUDGET_ADMIN} 不保持時）する二重防衛とする。</p>
     *
     * <p>レスポンス形式は他 API と整合させるため {@code Map.of("data", ...)} で {@code data} ラップ。
     * {@link com.mannschaft.app.common.ApiResponse} は {@code @JsonView} を持たないため
     * {@link MappingJacksonValue} と組み合わせると全フィールドがフィルタアウトされてしまう
     * （Jackson 仕様: {@code DEFAULT_VIEW_INCLUSION=false} がプロジェクト既定）。
     * Map ベースの簡易 wrap で同等のレスポンス形状を維持する。</p>
     */
    @GetMapping("/{id}/consumption-summary")
    @Operation(summary = "シフト予算消化サマリを取得 (BUDGET_ADMIN なら by_user 込み)")
    public MappingJacksonValue getConsumptionSummary(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        ConsumptionSummaryResponse response = summaryService.getConsumptionSummary(organizationId, id);

        // ApiResponse 互換の {"data": {...}} 形状を保ちつつ @JsonView を効かせるため Map で wrap
        MappingJacksonValue jacksonValue = new MappingJacksonValue(Map.of("data", response));
        jacksonValue.setSerializationView(resolveView(organizationId));
        return jacksonValue;
    }

    /**
     * 現在ユーザーの権限から最も詳細な View を解決する。
     *
     * <p>設計書 §9.3 のサンプルコードに準拠。
     * v1.2 クリーンカット方式: {@code BUDGET_ADMIN} 単独判定。
     * {@code MANAGE_SHIFTS+BUDGET_VIEW} の OR 後方互換ロジックは廃止済（V11.034 で自動付与）。</p>
     */
    private Class<?> resolveView(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return BudgetView.BudgetAdmin.class;
        }
        if (hasOrgPermission(currentUserId, organizationId, "BUDGET_ADMIN")) {
            return BudgetView.BudgetAdmin.class;
        }
        // Service 層で BUDGET_VIEW 必須を強制済のため、ここに到達した時点で BUDGET_VIEW は保有
        return BudgetView.BudgetViewer.class;
    }

    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (com.mannschaft.app.common.BusinessException e) {
            return false;
        }
    }
}
