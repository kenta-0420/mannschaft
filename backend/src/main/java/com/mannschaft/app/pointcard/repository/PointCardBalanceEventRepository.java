package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardBalanceEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 残高変動履歴リポジトリ（F18 Phase 3）。
 *
 * <p>スコープは「organization + 操作者」であり個人スコープではないため、
 * {@code AbstractUserOwnedRepository} は使わず {@code JpaRepository} を直接継承する。
 * {@code deleted_at} を持たないテーブルのため {@code AbstractTenantAwareRepository} も継承しない。
 */
@Repository
public interface PointCardBalanceEventRepository extends JpaRepository<PointCardBalanceEventEntity, UUID> {

    /**
     * 組織配下の残高変動履歴を新着順に取得する。
     */
    Page<PointCardBalanceEventEntity> findByOrganizationIdOrderByOperatedAtDesc(
            Long organizationId, Pageable pageable);

    /**
     * 組織 + プロバイダー絞り込みで新着順に取得する。
     */
    Page<PointCardBalanceEventEntity> findByOrganizationIdAndProviderIdOrderByOperatedAtDesc(
            Long organizationId, UUID providerId, Pageable pageable);

    /**
     * 単一カードの残高変動履歴を新着順に取得する（顧客側マイページ用）。
     */
    List<PointCardBalanceEventEntity> findByCardIdOrderByOperatedAtDesc(UUID cardId);

    /**
     * 指定 event を元 event とする返金履歴を取得する（多重返金検知用）。
     */
    List<PointCardBalanceEventEntity> findByRefundOfEventId(UUID refundOfEventId);

    /**
     * 組織配下の残高変動履歴件数を返す（管理ダッシュボード集計用）。
     */
    long countByOrganizationId(Long organizationId);
}
