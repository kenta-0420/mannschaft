package com.mannschaft.app.payment.connect;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Connect アカウントリポジトリ。
 *
 * <p>{@code organization_id} を保持するテナントスコープのため
 * {@link AbstractTenantAwareRepository} を継承する（CLAUDE.md 原則7）。
 * 受領口座の解決は {@code ConnectChargeService} 等から利用される。</p>
 */
public interface ConnectAccountRepository
        extends AbstractTenantAwareRepository<ConnectAccountEntity, UUID> {

    /** 受領主体（USER/TEAM/ORG × scope_id）のアクティブな Connect アカウントを取得する。 */
    Optional<ConnectAccountEntity> findByScopeKindAndScopeIdAndDeletedAtIsNull(
            ScopeKind scopeKind, Long scopeId);

    /** Stripe アカウントID（acct_xxx）から逆引きする（Webhook ハンドラ用）。 */
    Optional<ConnectAccountEntity> findByStripeAccountId(String stripeAccountId);

    /** 払出可能な Connect アカウントを取得する（解決時の payouts_enabled 判定用）。 */
    List<ConnectAccountEntity> findByPayoutsEnabledTrueAndDeletedAtIsNull();
}
