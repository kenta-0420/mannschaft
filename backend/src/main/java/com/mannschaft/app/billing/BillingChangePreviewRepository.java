package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6b-1: 一回消費の PLAN 変更 preview リポジトリ（{@code billing_change_previews}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（{@link BillingContractOperationRepository} 前例）。</p>
 */
public interface BillingChangePreviewRepository
        extends AbstractTenantAwareRepository<BillingChangePreviewEntity, UUID> {

    /** 主キーで取得する（deleted_at 除外・Checkout 直前の再照合で使用）。 */
    Optional<BillingChangePreviewEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 主キー＋契約で取得する（AC-10: 他契約 [他スコープ] の preview を IDOR で拾わないための絞り込み）。
     */
    Optional<BillingChangePreviewEntity> findByIdAndContractIdAndDeletedAtIsNull(UUID id, UUID contractId);

    /** 契約単位で未消費（{@code consumed_at IS NULL}）の preview を取得する。 */
    Optional<BillingChangePreviewEntity> findByContractIdAndConsumedAtIsNullAndDeletedAtIsNull(UUID contractId);

    /**
     * AC-7/AC-8: 一回消費の CAS（{@code consumed_at IS NULL AND expires_at>now AND version=?}）。
     *
     * @return 更新できた行数（0 なら既に消費済み・失効・並行敗者のいずれか）
     */
    @Modifying
    @Query("UPDATE BillingChangePreviewEntity p SET p.consumedAt = :now, p.version = p.version + 1 "
            + "WHERE p.id = :id AND p.consumedAt IS NULL AND p.expiresAt > :now AND p.version = :expectedVersion")
    int consumeIfValid(@Param("id") UUID id, @Param("now") Instant now,
                       @Param("expectedVersion") Long expectedVersion);
}
