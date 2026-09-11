package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6a: 契約操作 lease リポジトリ（{@code active_billing_contract_operation_pointers}）。
 *
 * <p>{@code deleted_at} を持たない物理 DELETE 運用のため
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} は継承しない
 * （{@link ActiveContractPointerRepository} 前例と同型）。素の {@link JpaRepository} とし、主キーは
 * {@code contract_id} そのもの（{@link ActiveBillingContractOperationPointerEntity} 参照）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（lease 取得・解放 Service は別部隊）。</p>
 */
public interface ActiveBillingContractOperationPointerRepository
        extends JpaRepository<ActiveBillingContractOperationPointerEntity, UUID> {

    /** 操作IDで lease を逆引きする（{@code uk_abcop_operation} により最大1件）。 */
    Optional<ActiveBillingContractOperationPointerEntity> findByOperationId(UUID operationId);

    /**
     * 契約IDかつ操作ID一致で lease を物理 DELETE する（操作 terminal 確定時の解放。
     * 戻り値の削除件数で「自分が今も lease の持ち主だったか」を検証できる）。
     */
    @Modifying
    @Query("DELETE FROM ActiveBillingContractOperationPointerEntity p "
            + "WHERE p.contractId = :contractId AND p.operationId = :operationId")
    int hardDeleteByContractIdAndOperationId(
            @Param("contractId") UUID contractId, @Param("operationId") UUID operationId);
}
