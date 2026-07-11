package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * F20.1: 契約リポジトリ（{@code billing_contracts}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（escrow/fee_recovery_balances 前例・
 * 設計書 01 §0）。USER スコープ行は {@code organization_id=NULL} を許容する
 * （この場合テナント絞り込みメソッドの対象外になるだけで実害なし。USER スコープの照会は
 * scope 系 finder を使う）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（契約作成・解約・プラン変更 Service は別部隊）。</p>
 */
public interface BillingContractRepository
        extends AbstractTenantAwareRepository<BillingContractEntity, UUID> {

    /** スコープ×状態で契約一覧を取得する（アクティブ契約の存在確認・履歴照会に使用）。 */
    List<BillingContractEntity> findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(
            EntitlementScopeKind scopeKind, Long scopeId, ContractStatus status);

    /** スコープの契約履歴（全状態）を新しい順で取得する。 */
    List<BillingContractEntity> findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByContractedAtDesc(
            EntitlementScopeKind scopeKind, Long scopeId);

    /** 主キーで取得する（deleted_at 除外）。取消・プラン変更時の対象契約解決に使用。 */
    java.util.Optional<BillingContractEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * スコープ×状態集合で契約を取得する（退会 purge 連動 AC-45: USER スコープの
     * PENDING/ACTIVE/PAST_DUE 契約の一括解約に使用）。
     */
    List<BillingContractEntity> findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
            EntitlementScopeKind scopeKind, Long scopeId, java.util.Collection<ContractStatus> statuses);

    /**
     * Stripe Subscription ID（{@code psp_subscription_ref}）で契約を逆引きする（実決済 D-2・webhook ルーティング）。
     * {@code uk_bc_psp_subscription} により最大 1 件。invoice.* / customer.subscription.deleted の billing/membership
     * 分離判定に使用する（ヒットすれば billing 所有・なければ F08.9 会費側）。
     */
    java.util.Optional<BillingContractEntity> findByPspSubscriptionRefAndDeletedAtIsNull(String pspSubscriptionRef);

    /** 指定プランを参照する当該状態の契約が存在するか（シスアド マスタ DELETE の参照中判定・02 §4）。 */
    boolean existsByPlanKeyAndStatusAndDeletedAtIsNull(String planKey, ContractStatus status);

    /** 指定機能を参照する当該状態の ADDON 契約が存在するか（シスアド マスタ DELETE の参照中判定・02 §4）。 */
    boolean existsByFeatureKeyAndStatusAndDeletedAtIsNull(String featureKey, ContractStatus status);

    /**
     * シスアド 契約横断検索（設計書 02 §4）。scopeKind / scopeId / status は任意フィルタ（null=無条件）。
     * 契約日時の新しい順で返す。
     */
    @Query("SELECT c FROM BillingContractEntity c WHERE c.deletedAt IS NULL "
            + "AND (:scopeKind IS NULL OR c.scopeKind = :scopeKind) "
            + "AND (:scopeId IS NULL OR c.scopeId = :scopeId) "
            + "AND (:status IS NULL OR c.status = :status) "
            + "ORDER BY c.contractedAt DESC")
    Page<BillingContractEntity> searchContracts(
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            @Param("status") ContractStatus status,
            Pageable pageable);
}
