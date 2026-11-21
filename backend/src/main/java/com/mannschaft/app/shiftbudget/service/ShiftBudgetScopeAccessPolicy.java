package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * シフト予算の組織スコープの認可（実所属＋権限。CMP-260909-2152 確定設計 r12 §9・御裁定 追加4(1)）。
 *
 * <p>手動締め・締め期間一覧・締め日設定の入口は、SYSTEM_ADMIN 単独（組織に所属しない）・他組織を 403 で拒否する。
 * 組織への実所属があり、かつ当該権限（参照は {@code BUDGET_VIEW}、変更・締めは {@code BUDGET_ADMIN}）を持つ場合だけ通す。
 * 認可は Facade から呼ぶ（TX 本体・段取り役はここへ到達しない。R4）。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetScopeAccessPolicy {

    private static final String ORGANIZATION = "ORGANIZATION";

    private final AccessControlService accessControlService;

    /** 参照（BUDGET_VIEW。BUDGET_ADMIN 保有者は階層上 VIEW も持つ）。認可呼び出しは Wave4 の探索深さのため本メソッドに直接置く。 */
    public void requireBudgetView(Long userId, Long organizationId) {
        if (!(accessControlService.isMember(userId, organizationId, ORGANIZATION)
                && accessControlService.hasPermission(userId, organizationId, ORGANIZATION, "BUDGET_VIEW"))) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    /** 変更・締め（BUDGET_ADMIN）。 */
    public void requireBudgetAdmin(Long userId, Long organizationId) {
        if (!(accessControlService.isMember(userId, organizationId, ORGANIZATION)
                && accessControlService.hasPermission(userId, organizationId, ORGANIZATION, "BUDGET_ADMIN"))) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED);
        }
    }
}
