package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6a: 契約操作 Saga リポジトリ（{@code billing_contract_operations}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（{@link BillingContractRepository} 前例）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（起票・遷移・reconcile Service は別部隊）。</p>
 */
public interface BillingContractOperationRepository
        extends AbstractTenantAwareRepository<BillingContractOperationEntity, UUID> {

    /** 契約×冪等キーで取得する（{@code uk_bco_idempotency}・冪等リトライの既存行解決に使用）。 */
    Optional<BillingContractOperationEntity> findByContractIdAndIdempotencyKeyAndDeletedAtIsNull(
            UUID contractId, String idempotencyKey);

    /** 契約の操作履歴をステータス絞り込みで取得する（進行中操作の存在確認に使用）。 */
    List<BillingContractOperationEntity> findByContractIdAndStatusAndDeletedAtIsNull(
            UUID contractId, BillingOperationStatus status);

    /** 主キーで取得する（deleted_at 除外）。 */
    Optional<BillingContractOperationEntity> findByIdAndDeletedAtIsNull(UUID id);
}
