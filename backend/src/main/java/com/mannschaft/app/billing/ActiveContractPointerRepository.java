package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * F20.1: アクティブ契約ポインタリポジトリ（{@code active_contract_pointers}）。
 *
 * <p>{@code deleted_at} を持たない物理 DELETE 運用のため
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} は継承しない
 * （同基底は {@code ...DeletedAtIsNull} 派生と {@code deleted_at} 列を前提とする）。
 * 素の {@link JpaRepository} とし、検索は「スロットキー」
 * （scope_kind, scope_id, contract_kind, addon_feature_key）で行う（設計書 01 §3.1.1）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（契約作成・解約・切替 Service は別部隊）。</p>
 */
public interface ActiveContractPointerRepository
        extends JpaRepository<ActiveContractPointerEntity, UUID> {

    /**
     * スロットキーでアクティブポインタを取得する。契約作成時の重複判定・切替時の対象解決に使用。
     * PLAN のときは {@code addonFeatureKey=""} を渡す（設計書 01 §3.1.1）。
     */
    Optional<ActiveContractPointerEntity> findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
            EntitlementScopeKind scopeKind, Long scopeId, ContractKind contractKind, String addonFeatureKey);

    /**
     * スロットキーでポインタを物理 DELETE する（解約時。戻り値の削除件数で成否を検証する）。
     *
     * <p>解約時にスロットを解放し、次回契約時に再 INSERT 可能にする（設計書 01 §3.1.1 運用フロー）。</p>
     */
    @Modifying
    @Query("DELETE FROM ActiveContractPointerEntity p WHERE p.scopeKind = :scopeKind "
            + "AND p.scopeId = :scopeId AND p.contractKind = :contractKind "
            + "AND p.addonFeatureKey = :addonFeatureKey")
    int hardDeleteBySlot(
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            @Param("contractKind") ContractKind contractKind,
            @Param("addonFeatureKey") String addonFeatureKey);
}
