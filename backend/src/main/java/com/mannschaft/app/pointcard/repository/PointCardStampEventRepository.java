package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardStampEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * スタンプ押印履歴リポジトリ。
 *
 * <p>スコープは「organization + 押印者」であり個人スコープではないため、
 * {@code AbstractUserOwnedRepository} は使わず {@code JpaRepository} を直接継承する。
 * {@code organization_id} 絞り込みのみで使うが、論理削除カラムを持たないため
 * {@code AbstractTenantAwareRepository} も継承しない（{@code deleted_at} を持たないテーブル）。
 */
@Repository
public interface PointCardStampEventRepository extends JpaRepository<PointCardStampEventEntity, UUID> {

    /**
     * 組織配下の押印履歴を新着順に取得する。
     */
    Page<PointCardStampEventEntity> findByOrganizationIdOrderByPressedAtDesc(
            Long organizationId, Pageable pageable);

    /**
     * 組織 + プロバイダー絞り込みで新着順に取得する。
     */
    Page<PointCardStampEventEntity> findByOrganizationIdAndProviderIdOrderByPressedAtDesc(
            Long organizationId, UUID providerId, Pageable pageable);

    /**
     * 単一カードの押印履歴を新着順に取得する（顧客側マイページ用）。
     */
    List<PointCardStampEventEntity> findByCardIdOrderByPressedAtDesc(UUID cardId);

    /**
     * 組織配下の押印履歴件数を返す（管理ダッシュボード集計用）。
     */
    long countByOrganizationId(Long organizationId);
}
