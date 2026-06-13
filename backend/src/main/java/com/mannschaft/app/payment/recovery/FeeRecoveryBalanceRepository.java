package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済 §6.3: 未回収手数料残高リポジトリ。
 *
 * <p>{@code organization_id} を保持するテナントスコープのため
 * {@link AbstractTenantAwareRepository} を継承する（CLAUDE.md 原則7）。
 * このフェーズでは Entity/Repo の永続・制約のみ（回収計算 Service は後続第三陣）。</p>
 */
public interface FeeRecoveryBalanceRepository
        extends AbstractTenantAwareRepository<FeeRecoveryBalanceEntity, UUID> {

    /**
     * payee（Connect アカウント）×通貨のアクティブな残高行を取得する。
     * UNIQUE {@code uk_frb_account_currency} により最大 1 行（論理削除を除く）。
     */
    Optional<FeeRecoveryBalanceEntity> findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(
            UUID connectAccountId, String currency);
}
