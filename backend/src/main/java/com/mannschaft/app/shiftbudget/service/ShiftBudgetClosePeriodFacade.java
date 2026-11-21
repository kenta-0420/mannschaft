package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.transaction.NonTransactionalEntryGuard;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 締め期間一覧・未計上勤務の参照の入口（認可・機能フラグを TX の外で行う Facade。CMP-260909-2152 確定設計 r12 §2・§9）。
 *
 * <p>すべて組織 BUDGET_VIEW。他組織の割当は実在を漏らさず 404。SYSTEM_ADMIN 単独は 403。</p>
 */
@RequiredArgsConstructor
@Service
public class ShiftBudgetClosePeriodFacade {

    /** 期間一覧の範囲の上限（月数）。 */
    static final int MAX_LIST_MONTHS = 36;

    private final ShiftBudgetScopeAccessPolicy policy;
    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetClosePeriodResolver periodResolver;
    private final ShiftBudgetUncountedShiftService uncountedShiftService;

    /** 組織の締め期間一覧（期間キー [from, to]）。from&gt;to または範囲が36か月超は 400。 */
    public List<ShiftBudgetClosePeriodResolver.ResolvedPeriod> listByOrganization(Long organizationId, YearMonth from,
                                                                                   YearMonth to) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetView(userId, organizationId);
        if (from.isAfter(to) || ChronoUnit.MONTHS.between(from, to) + 1 > MAX_LIST_MONTHS) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return periodResolver.list(organizationId, from, to);
    }

    /** 割当と重なる締め期間の一覧。 */
    public List<ShiftBudgetClosePeriodResolver.ResolvedPeriod> listByAllocation(Long organizationId, Long allocationId) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetView(userId, organizationId);
        return uncountedShiftService.periodsOfAllocation(organizationId, allocationId);
    }

    /** 締め済み期間の未計上勤務（枠×人で計上済みでない勤務）。未締めの期間キーは 409。 */
    public List<ShiftBudgetUncountedShiftService.UncountedShift> uncountedShifts(Long organizationId, Long allocationId,
                                                                                  YearMonth periodKey) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetView(userId, organizationId);
        return uncountedShiftService.list(organizationId, allocationId, periodKey);
    }
}
