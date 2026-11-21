package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.transaction.NonTransactionalEntryGuard;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * 締め日設定の参照・変更の入口（認可・機能フラグ・監査を TX の外で行う Facade。CMP-260909-2152 確定設計 r12 §2・§9）。
 *
 * <p>参照は組織 BUDGET_VIEW、変更は組織 BUDGET_ADMIN。SYSTEM_ADMIN 単独・他組織は 403。</p>
 */
@RequiredArgsConstructor
@Service
public class ShiftBudgetClosingSettingFacade {

    private final ShiftBudgetScopeAccessPolicy policy;
    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetClosingSettingService settingService;
    private final AuditLogService auditLogService;

    /** 締め日設定を取得する（設定なしは closing_day=0・revision=0）。 */
    public ShiftBudgetClosingSettingService.ClosingSettingView get(Long organizationId) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetView(userId, organizationId);
        return settingService.get(organizationId);
    }

    /** 締め日を変更する（理由必須・履歴は追記のみ）。 */
    public ShiftBudgetClosingSettingService.ClosingSettingView change(Long organizationId, long expectedRevision,
                                                                       int closingDay, YearMonth effectiveKey,
                                                                       String reason) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        Long userId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        policy.requireBudgetAdmin(userId, organizationId);

        // 同じ revision の同時変更はデッドロックの犠牲が出うる。犠牲は TX の外で再実行すると、他方の確定後の revision を読んで 409 になる
        ShiftBudgetClosingSettingService.ClosingSettingView view = ShiftBudgetDeadlockRetry.run("closingDayChange",
                () -> settingService.change(organizationId, userId, expectedRevision, closingDay, effectiveKey, reason));
        auditLogService.record("SHIFT_BUDGET_CLOSING_DAY_CHANGED", userId, null, null, organizationId, null, null, null,
                String.format("{\"revision\":%d,\"effective_period_key\":\"%s\",\"closing_day\":%d}",
                        view.revision(), effectiveKey, closingDay));
        return view;
    }
}
