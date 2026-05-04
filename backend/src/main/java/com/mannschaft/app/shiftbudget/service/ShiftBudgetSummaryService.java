package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * F08.7 シフト予算消化サマリ サービス（API #5 / Phase 9-β）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。</p>
 *
 * <p><strong>マスター御裁可 Q2</strong>: Phase 9-β 中、{@code by_user} は
 * 常に空配列 + {@code flags=["BY_USER_HIDDEN"]} を返却する（クリーンカット精神）。
 * 9-δ で BUDGET_ADMIN クリーンカット移行時に正規化（権限ありなら個人別内訳を返す）する。</p>
 *
 * <p>{@code alerts} は警告機能 (9-δ) 未実装のため常に空配列。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftBudgetSummaryService {

    /** Phase 9-β で常に flags に追加される識別子 */
    public static final String FLAG_BY_USER_HIDDEN = "BY_USER_HIDDEN";

    /** 消化率の小数桁数（0.8167 のように 4 桁） */
    private static final int RATE_SCALE = 4;

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final TodoBudgetLinkRepository todoBudgetLinkRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;

    /**
     * 指定 allocation の消化サマリを返す。
     *
     * <p>権限: {@code BUDGET_VIEW}</p>
     */
    public ConsumptionSummaryResponse getConsumptionSummary(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        ShiftBudgetAllocationEntity allocation = allocationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(allocationId, organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND));

        BigDecimal allocated = allocation.getAllocatedAmount();
        BigDecimal consumed = allocation.getConsumedAmount();
        BigDecimal confirmed = allocation.getConfirmedAmount();
        // planned = consumed - confirmed（CHECK で負数禁止）。負値防止のため max(0)
        BigDecimal planned = consumed.subtract(confirmed).max(BigDecimal.ZERO);
        BigDecimal remaining = allocated.subtract(consumed);

        BigDecimal rate = calculateConsumptionRate(allocated, consumed);
        String status = determineStatus(rate);

        // by_user は Q2 御裁可により Phase 9-β 中は常に空配列
        // flags は常に BY_USER_HIDDEN を含める（v1.2 形状確定ルール）
        List<String> flags = List.of(FLAG_BY_USER_HIDDEN);

        return ConsumptionSummaryResponse.builder()
                .allocationId(allocation.getId())
                .allocatedAmount(allocated)
                .consumedAmount(consumed)
                .confirmedAmount(confirmed)
                .plannedAmount(planned)
                .remainingAmount(remaining)
                .consumptionRate(rate)
                .status(status)
                .flags(flags)
                .alerts(Collections.emptyList())  // 9-δ まで常に空配列
                .byUser(Collections.emptyList())  // Q2 御裁可: 常に空配列
                .build();
    }

    /**
     * 消化率 = consumed_amount / allocated_amount。
     *
     * <p>allocated_amount = 0 の場合は 0 を返す（境界ケース、設計書 §11 「予算ゼロ円」参照）。</p>
     */
    private BigDecimal calculateConsumptionRate(BigDecimal allocated, BigDecimal consumed) {
        if (allocated.signum() == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return consumed.divide(allocated, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 消化率からステータス文字列を決定する（設計書 §6.2.3 4段階）。
     *
     * <ul>
     *   <li>{@code rate < 0.80} → {@code OK}</li>
     *   <li>{@code 0.80 ≤ rate < 1.00} → {@code WARN}</li>
     *   <li>{@code 1.00 ≤ rate < 1.20} → {@code EXCEEDED}</li>
     *   <li>{@code rate ≥ 1.20} → {@code SEVERE_EXCEEDED}</li>
     * </ul>
     */
    private String determineStatus(BigDecimal rate) {
        BigDecimal r = rate;
        if (r.compareTo(new BigDecimal("0.80")) < 0) {
            return "OK";
        } else if (r.compareTo(BigDecimal.ONE) < 0) {
            return "WARN";
        } else if (r.compareTo(new BigDecimal("1.20")) < 0) {
            return "EXCEEDED";
        }
        return "SEVERE_EXCEEDED";
    }

    /**
     * テスト容易性 / 9-δ 移行のための内部ヘルパー（package-private）。
     * 現状未使用だが、9-δ で BUDGET_ADMIN クリーンカット時に
     * {@code by_user} 集計を再活性化する際の起点。
     */
    @SuppressWarnings("unused")
    List<ShiftBudgetConsumptionEntity> findAliveConsumptionsForFutureUse(Long allocationId) {
        return consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.PLANNED,
                        ShiftBudgetConsumptionStatus.CONFIRMED));
    }

    /**
     * プロジェクト消化額（直接経路 + TODO 経由）の合計を返す（Phase 9-γ 追加）。
     *
     * <p>設計書 §4.3 に厳密準拠:</p>
     * <ul>
     *   <li>直接経路: {@code shift_schedules.linked_project_id = projectId} のシフトに紐付く全消化を SUM</li>
     *   <li>TODO 経由: {@code todo_budget_links.project_id = projectId} 経由で按分集計
     *       （直接経路と重複する分は除外、{@code s.linked_project_id IS NULL OR != :projectId}）</li>
     *   <li>合計 = 直接経路 + TODO 経由</li>
     * </ul>
     *
     * <p>多テナント分離は Repository の native query 内
     * {@code shift_budget_allocations.organization_id = :organizationId} で保証する。</p>
     *
     * <p>権限: {@code BUDGET_VIEW}</p>
     *
     * @param organizationId 組織ID（多テナント分離キー）
     * @param projectId      プロジェクトID
     * @return 合計消化額（直接 + TODO 経由）
     */
    public BigDecimal getProjectConsumedAmount(Long organizationId, Long projectId) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        BigDecimal direct = todoBudgetLinkRepository.sumDirectAmountForProject(projectId, organizationId);
        BigDecimal viaTodo = todoBudgetLinkRepository.sumViaTodoAmountForProject(projectId, organizationId);

        BigDecimal safeDirect = direct != null ? direct : BigDecimal.ZERO;
        BigDecimal safeViaTodo = viaTodo != null ? viaTodo : BigDecimal.ZERO;
        return safeDirect.add(safeViaTodo);
    }

    private void requireBudgetView(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時に置換
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
