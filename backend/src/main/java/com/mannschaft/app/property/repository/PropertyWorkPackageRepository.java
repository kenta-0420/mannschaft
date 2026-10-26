package com.mannschaft.app.property.repository;

import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 物件履歴パッケージリポジトリ。
 * F09.13 設計書 §4 履歴パッケージ API のクエリパターンに対応。
 */
public interface PropertyWorkPackageRepository
        extends JpaRepository<PropertyWorkPackageEntity, Long>,
                JpaSpecificationExecutor<PropertyWorkPackageEntity> {

    /**
     * ID で未削除のパッケージを取得する。
     */
    Optional<PropertyWorkPackageEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープに紐づく未削除パッケージをページング取得する。
     */
    Page<PropertyWorkPackageEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ × ステータスでフィルタする。
     */
    Page<PropertyWorkPackageEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            String scopeType, Long scopeId, WorkPackageStatus status, Pageable pageable);

    /**
     * スコープ × 工事種別でフィルタする。
     */
    Page<PropertyWorkPackageEntity> findByScopeTypeAndScopeIdAndWorkTypeAndDeletedAtIsNull(
            String scopeType, Long scopeId, WorkType workType, Pageable pageable);

    /**
     * 居室別履歴。
     */
    List<PropertyWorkPackageEntity> findByDwellingUnitIdAndDeletedAtIsNullOrderByActualEndDateDesc(
            Long dwellingUnitId);

    /**
     * 事故起点パッケージ検索（F07.6 Incident → Package 双方向リンク用）。
     */
    Optional<PropertyWorkPackageEntity> findByIncidentIdAndDeletedAtIsNull(Long incidentId);

    /**
     * 業者別履歴。
     */
    List<PropertyWorkPackageEntity> findByVendorIdAndDeletedAtIsNullOrderByActualEndDateDesc(
            Long vendorId);

    /**
     * F08.6 BudgetTransaction との連携 — 該当 transaction を参照するパッケージを取得する。
     */
    List<PropertyWorkPackageEntity> findByBudgetTransactionIdAndDeletedAtIsNull(
            Long budgetTransactionId);

    /**
     * ガントビュー用 — 計画日が指定範囲に重なる未削除パッケージを取得する。
     */
    @Query("""
            SELECT p FROM PropertyWorkPackageEntity p
            WHERE p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.deletedAt IS NULL
              AND p.plannedStartDate IS NOT NULL
              AND p.plannedEndDate IS NOT NULL
              AND p.plannedStartDate <= :to
              AND p.plannedEndDate >= :from
            ORDER BY p.plannedStartDate ASC
            """)
    List<PropertyWorkPackageEntity> findForGantt(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 重要事項説明書（F09.14）用 — 開示可能な完了済みパッケージを取得する。
     */
    @Query("""
            SELECT p FROM PropertyWorkPackageEntity p
            WHERE p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.isDisclosable = true
              AND p.deletedAt IS NULL
              AND p.actualEndDate IS NOT NULL
              AND p.actualEndDate >= :from
            ORDER BY p.actualEndDate DESC
            """)
    List<PropertyWorkPackageEntity> findDisclosable(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDate from);

    /**
     * カテゴリサジェスト用 — スコープ別に category 別件数を集計する。
     */
    @Query("""
            SELECT p.category, COUNT(p)
            FROM PropertyWorkPackageEntity p
            WHERE p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.deletedAt IS NULL
              AND p.category IS NOT NULL
              AND p.createdAt >= :since
            GROUP BY p.category
            ORDER BY COUNT(p) DESC
            """)
    List<Object[]> aggregateCategoriesSince(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("since") java.time.LocalDateTime since);
}
