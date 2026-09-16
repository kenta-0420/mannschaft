package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6b-1: PLAN 変更 Saga 詳細行リポジトリ（{@code billing_contract_changes}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（{@link BillingContractOperationRepository} 前例）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（起票・遷移 Service は別部隊）。</p>
 */
public interface BillingContractChangeRepository
        extends AbstractTenantAwareRepository<BillingContractChangeEntity, UUID> {

    /** operation 単位で取得する（{@code uk_bcc_operation}・1:1 対応）。 */
    Optional<BillingContractChangeEntity> findByOperationIdAndDeletedAtIsNull(UUID operationId);

    /** 契約×冪等キーで取得する（{@code uk_bcc_idempotency}・冪等リトライの既存行解決に使用）。 */
    Optional<BillingContractChangeEntity> findByContractIdAndIdempotencyKeyAndDeletedAtIsNull(
            UUID contractId, String idempotencyKey);

    /** 契約の変更履歴をステータス絞り込みで取得する（進行中変更の存在確認に使用）。 */
    List<BillingContractChangeEntity> findByContractIdAndStatusAndDeletedAtIsNull(
            UUID contractId, BillingContractChangeStatus status);

    /** 主キーで取得する（deleted_at 除外）。 */
    Optional<BillingContractChangeEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * {@code stripe_invoice_ref}（{@code uk_bcc_invoice}）で取得する（PR6b-1 B群 AC-37〜43）。
     *
     * <p>invoice webhook が「この invoice は upgrade の差額請求である」と判定するための入口。
     * まだ bind されていない（{@code stripe_invoice_ref IS NULL}）場合は
     * {@link com.mannschaft.app.billing.BillingContractOperationSagaService} 側の
     * {@code operation_id} 逆引き（AC-42）で解決する。</p>
     */
    Optional<BillingContractChangeEntity> findByStripeInvoiceRefAndDeletedAtIsNull(String stripeInvoiceRef);
}
