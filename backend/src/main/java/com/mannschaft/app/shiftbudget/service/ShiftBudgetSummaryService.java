package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.dto.UserConsumptionDto;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F08.7 シフト予算消化サマリ サービス（API #5 / Phase 9-δ 第3段で正規化完了）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。</p>
 *
 * <p><strong>権限による表示切替（Phase 9-δ 第3段で正規化）</strong>:</p>
 * <ul>
 *   <li>{@code BUDGET_ADMIN} 保有時: {@code by_user} に user_id 別の実集計を返却 +
 *       {@code flags} は空配列 + {@code alerts} は実集計</li>
 *   <li>{@code BUDGET_VIEW} のみ: {@code by_user} は空配列 +
 *       {@code flags=["BY_USER_HIDDEN"]} + {@code alerts} は実集計</li>
 *   <li>BUDGET_VIEW も無し: {@link ShiftBudgetErrorCode#BUDGET_VIEW_REQUIRED} (403)</li>
 * </ul>
 *
 * <p>{@code by_user} の集計対象は status が {@link ShiftBudgetConsumptionStatus#PLANNED} か
 * {@link ShiftBudgetConsumptionStatus#CONFIRMED} の生存レコードのみ
 * （{@link ShiftBudgetConsumptionStatus#CANCELLED} は除外、設計書 §5.3 物理削除禁止運用）。</p>
 *
 * <p>{@code alerts} は組織配下の警告のうち、当該 allocation に紐付く全件
 * （承認済 / 未承認問わず）を {@code triggered_at DESC} で返却する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftBudgetSummaryService {

    /** {@code BUDGET_VIEW} のみで {@code by_user} を伏せる際の flags 識別子 */
    public static final String FLAG_BY_USER_HIDDEN = "BY_USER_HIDDEN";

    /** 消化率の小数桁数（0.8167 のように 4 桁） */
    private static final int RATE_SCALE = 4;

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final TodoBudgetLinkRepository todoBudgetLinkRepository;
    private final BudgetThresholdAlertRepository alertRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;

    /**
     * 指定 allocation の消化サマリを返す。
     *
     * <p>権限: {@code BUDGET_VIEW} 必須（無いと 403）。
     * {@code BUDGET_ADMIN} 保有時のみ {@code by_user} の実データを返す。</p>
     */
    public ConsumptionSummaryResponse getConsumptionSummary(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        boolean budgetAdmin = requireBudgetViewAndDetectAdmin(organizationId);

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

        // 永続化済 entity の id を優先し、未設定なら呼出パラメータの allocationId を使う（テスト容易性）
        Long resolvedId = allocation.getId() != null ? allocation.getId() : allocationId;

        // alerts は権限によらず実集計（個人別時給は含まないため漏洩リスクなし）
        List<AlertResponse> alerts = aggregateAlerts(resolvedId);

        // by_user は BUDGET_ADMIN 保有時のみ実集計、それ以外は空配列 + flags=["BY_USER_HIDDEN"]
        List<UserConsumptionDto> byUser;
        List<String> flags;
        if (budgetAdmin) {
            byUser = aggregateByUser(resolvedId);
            flags = Collections.emptyList();
        } else {
            byUser = Collections.emptyList();
            flags = List.of(FLAG_BY_USER_HIDDEN);
        }

        return ConsumptionSummaryResponse.builder()
                .allocationId(resolvedId)
                .allocatedAmount(allocated)
                .consumedAmount(consumed)
                .confirmedAmount(confirmed)
                .plannedAmount(planned)
                .remainingAmount(remaining)
                .consumptionRate(rate)
                .status(status)
                .flags(flags)
                .alerts(alerts)
                .byUser(byUser)
                .build();
    }

    /**
     * user_id 別の消化額・勤務時間集計を返す（{@code BUDGET_ADMIN} 保有時のみ呼ばれる）。
     *
     * <p>集計対象: {@code allocation_id} 配下の status PLANNED/CONFIRMED かつ生存レコード。
     * group by user_id、SUM(amount) と SUM(hours) を採る。</p>
     *
     * <p>並び順は user_id 昇順（決定論的、テスト容易性向上）。</p>
     */
    private List<UserConsumptionDto> aggregateByUser(Long allocationId) {
        List<ShiftBudgetConsumptionEntity> consumptions = consumptionRepository
                .findByAllocationIdAndStatusInAndDeletedAtIsNull(
                        allocationId,
                        List.of(ShiftBudgetConsumptionStatus.PLANNED,
                                ShiftBudgetConsumptionStatus.CONFIRMED));

        // user_id 昇順を維持するため LinkedHashMap + ソート済 List で構築
        Map<Long, BigDecimal[]> grouped = new LinkedHashMap<>();
        consumptions.stream()
                .sorted((a, b) -> Long.compare(a.getUserId(), b.getUserId()))
                .forEach(c -> {
                    BigDecimal[] sums = grouped.computeIfAbsent(
                            c.getUserId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    sums[0] = sums[0].add(c.getAmount());
                    sums[1] = sums[1].add(c.getHours());
                });

        return grouped.entrySet().stream()
                .map(e -> UserConsumptionDto.builder()
                        .userId(e.getKey())
                        .amount(e.getValue()[0])
                        .hours(e.getValue()[1])
                        .build())
                .toList();
    }

    /**
     * 当該 allocation に紐付く警告全件を {@code triggered_at DESC} で返す。
     *
     * <p>承認済 / 未承認の両方を含める（消化サマリ表示では時系列で全履歴を見せる）。</p>
     */
    private List<AlertResponse> aggregateAlerts(Long allocationId) {
        List<BudgetThresholdAlertEntity> alerts = alertRepository.findByAllocationIdOrderByTriggeredAtDesc(allocationId);
        return alerts.stream().map(AlertResponse::from).toList();
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
        requireBudgetViewAndDetectAdmin(organizationId);

        BigDecimal direct = todoBudgetLinkRepository.sumDirectAmountForProject(projectId, organizationId);
        BigDecimal viaTodo = todoBudgetLinkRepository.sumViaTodoAmountForProject(projectId, organizationId);

        BigDecimal safeDirect = direct != null ? direct : BigDecimal.ZERO;
        BigDecimal safeViaTodo = viaTodo != null ? viaTodo : BigDecimal.ZERO;
        return safeDirect.add(safeViaTodo);
    }

    /**
     * {@code BUDGET_VIEW} 権限を要求しつつ、同時に {@code BUDGET_ADMIN} 保有も判定する。
     *
     * <p>Phase 9-δ 第3段クリーンカット: 権限階層は BUDGET_VIEW ⊂ BUDGET_ADMIN として扱う
     * （V11.034 マイグレーションで BUDGET_ADMIN 保有者には自動付与済の前提）。
     * ただし防衛的に SystemAdmin と BUDGET_ADMIN を独立判定し、いずれかがあれば true を返す。</p>
     *
     * @return {@code true} なら BUDGET_ADMIN 相当（by_user 実データを返してよい）
     */
    private boolean requireBudgetViewAndDetectAdmin(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return true;
        }
        boolean hasView = hasOrgPermission(currentUserId, organizationId, "BUDGET_VIEW");
        if (!hasView) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
        return hasOrgPermission(currentUserId, organizationId, "BUDGET_ADMIN");
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
