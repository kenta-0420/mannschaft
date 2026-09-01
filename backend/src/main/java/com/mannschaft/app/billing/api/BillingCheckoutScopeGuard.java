package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PR4 checkout 系 application service の scope 認可 port 実装。
 *
 * <p>判定ロジックは持たず、利用者向け課金 API の唯一の判定正本である
 * {@link BillingAccessGuard#canManageByActorId(long, EntitlementScopeKind, Long)} へ委譲する
 * （{@code Authentication} 経路と本経路が同じメソッドを通る）。不許可は
 * {@link EntitlementErrorCode#SCOPE_FORBIDDEN}(403) で fail-closed に拒否する。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCheckoutScopeGuard implements BillingCheckoutAccessGuard {

    private final BillingAccessGuard billingAccessGuard;

    @Override
    public void check(long actorId, EntitlementScopeKind scopeKind, long scopeId) {
        if (!billingAccessGuard.canManageByActorId(actorId, scopeKind, scopeId)) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
    }
}
