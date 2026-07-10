package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

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
}
