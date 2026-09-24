package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PR5 Portal セッション発行（{@code portal-sessions}）の scope 認可 port 実装（AC-61）。
 *
 * <p>判定ロジックは持たず、利用者向け課金 API の唯一の判定正本である
 * {@link BillingAccessGuard#canManageByActorId(long, EntitlementScopeKind, Long)} へ委譲する。
 * 不許可は {@link EntitlementErrorCode#SCOPE_FORBIDDEN}(403) で fail-closed に拒否する。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCustomerPortalScopeGuard implements BillingCustomerPortalAccessGuard {

    private final BillingAccessGuard billingAccessGuard;

    @Override
    public void check(long actorId, EntitlementScopeKind scopeKind, long scopeId) {
        if (!billingAccessGuard.canManageByActorId(actorId, scopeKind, scopeId)) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
    }
}
