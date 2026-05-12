package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 5 段階エスカレーションリポジトリ（F09.15）。
 *
 * <p>1 居住者 1 エスカのため、{@link #findByResidentRegistryIdAndDeletedAtIsNull(Long)}
 * は最大 1 件を返す {@link Optional} を返却する。
 */
public interface DelinquencyEscalationRepository
        extends AbstractTenantAwareRepository<DelinquencyEscalationEntity, UUID> {

    /** 居住者単位のエスカレーション（最大 1 件）。 */
    Optional<DelinquencyEscalationEntity> findByResidentRegistryIdAndDeletedAtIsNull(
            Long residentRegistryId);

    /**
     * 段階別の進行中エスカ一覧（凍結・解決済みは除外）。
     * 設計書 §7.4 5 段階エスカレーションバッチで使用。
     */
    List<DelinquencyEscalationEntity> findByCurrentStageAndFrozenAtIsNullAndResolvedAtIsNullAndDeletedAtIsNull(
            String currentStage);

    /** 組織配下の進行中エスカ一覧（理事長ダッシュボード用）。 */
    List<DelinquencyEscalationEntity> findByOrganizationIdAndResolvedAtIsNullAndDeletedAtIsNull(
            Long organizationId);
}
