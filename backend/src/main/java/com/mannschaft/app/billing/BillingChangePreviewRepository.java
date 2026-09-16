package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6b-1: 一回消費の PLAN 変更 preview リポジトリ（{@code billing_change_previews}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（{@link BillingContractOperationRepository} 前例）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（発行・消費 Service は別部隊）。</p>
 */
public interface BillingChangePreviewRepository
        extends AbstractTenantAwareRepository<BillingChangePreviewEntity, UUID> {

    /** 主キーで取得する（deleted_at 除外・Checkout 直前の再照合で使用）。 */
    Optional<BillingChangePreviewEntity> findByIdAndDeletedAtIsNull(UUID id);

    /** 契約単位で未消費（{@code consumed_at IS NULL}）の preview を取得する。 */
    Optional<BillingChangePreviewEntity> findByContractIdAndConsumedAtIsNullAndDeletedAtIsNull(UUID contractId);
}
