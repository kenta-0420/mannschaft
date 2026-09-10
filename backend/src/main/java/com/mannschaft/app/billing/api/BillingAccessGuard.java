package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingAccessRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 利用者向け課金 API の管理権限を一元判定する。 */
@Component("billingAccessGuard")
@RequiredArgsConstructor
public class BillingAccessGuard {

    /** TEAM scope の課金管理を DEPUTY_ADMIN へ明示付与する permission 名。 */
    public static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";
    /** ORG scope の課金管理を DEPUTY_ADMIN へ明示付与する permission 名。 */
    public static final String ORGANIZATION_PERMISSION = "MANAGE_ORGANIZATION_BILLING";

    private final BillingAccessRepository billingAccessRepository;

    /**
     * USER 本人、又は同一 TEAM/ORG の ADMIN・課金権限を明示付与された DEPUTY_ADMIN を許可する。
     * SYSTEM_ADMIN の権限文字列だけでは短絡許可しない。
     */
    public boolean canManage(
            Authentication authentication,
            EntitlementScopeKind scopeKind,
            Long scopeId) {
        Long userId = resolveUserId(authentication);
        if (userId == null) {
            return false;
        }
        return evaluate(userId, scopeKind, scopeId);
    }

    /**
     * 解決済み actorId で同一判定を行う（{@code Authentication} を持たない
     * application service 経路の唯一の判定入口。両経路がこのメソッドを通る）。
     */
    public boolean canManageByActorId(long actorId, EntitlementScopeKind scopeKind, Long scopeId) {
        return evaluate(Long.valueOf(actorId), scopeKind, scopeId);
    }

    private boolean evaluate(Long userId, EntitlementScopeKind scopeKind, Long scopeId) {
        if (userId == null || scopeKind == null || scopeId == null) {
            return false;
        }
        if (scopeKind == EntitlementScopeKind.USER) {
            return userId.equals(scopeId);
        }
        if (billingAccessRepository.existsAdmin(userId, scopeKind, scopeId)) {
            return true;
        }
        String permissionName = switch (scopeKind) {
            case TEAM -> TEAM_PERMISSION;
            case ORG -> ORGANIZATION_PERMISSION;
            case USER -> throw new IllegalStateException("USER scope is handled before permission resolution");
        };
        return billingAccessRepository.existsDeputyPermissionGroup(
                userId, scopeKind, scopeId, permissionName);
    }

    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
