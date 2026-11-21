package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.transaction.NonTransactionalEntryGuard;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * 手動締め（API #11）の入口（認可・機能フラグ・監査を TX の外で行う Facade。CMP-260909-2152 確定設計 r12 §9）。
 *
 * <p>認可は組織 BUDGET_ADMIN のみ（SYSTEM_ADMIN 単独・BUDGET_VIEW のみ・他組織は 403）。締めの本体は認可を持たない段取り役
 * {@link MonthlyShiftBudgetCloseCoordinator}。{@code @Transactional} は付けない（段取り役は TX 内から呼べない）。</p>
 */
@RequiredArgsConstructor
@Service
public class MonthlyShiftBudgetCloseFacade {

    private final ShiftBudgetScopeAccessPolicy policy;
    private final ShiftBudgetFeatureService featureService;
    private final MonthlyShiftBudgetCloseCoordinator coordinator;
    private final ShiftBudgetClosePeriodResolver periodResolver;
    private final AuditLogService auditLogService;

    /** 手動締めの結果と、締めた期間。 */
    public record CloseOutcome(MonthlyShiftBudgetCloseCoordinator.CloseResult result,
                               ShiftBudgetClosePeriodResolver.ResolvedPeriod period) {
    }

    /** 指定組織×期間キーを締める（各割当の未締め終了済みの過去期間を古い順に先に締める）。 */
    public CloseOutcome close(Long organizationId, YearMonth periodKey) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetAdmin(userId, organizationId);

        MonthlyShiftBudgetCloseCoordinator.CloseResult result = coordinator.close(organizationId, periodKey);
        ShiftBudgetClosePeriodResolver.ResolvedPeriod period = periodResolver.resolve(organizationId, periodKey);
        auditLogService.record("SHIFT_BUDGET_MONTHLY_CLOSED", userId, null, null, organizationId, null, null, null,
                String.format("{\"trigger\":\"MANUAL\",\"period_key\":\"%s\",\"period_start\":\"%s\",\"period_end\":\"%s\","
                                + "\"closed_allocations\":%d,\"failed_allocations\":%d,\"closed_consumption_count\":%d}",
                        periodKey, period.start(), period.end(), result.closedAllocations(), result.failedAllocations(),
                        result.closedConsumptions()));
        return new CloseOutcome(result, period);
    }
}
