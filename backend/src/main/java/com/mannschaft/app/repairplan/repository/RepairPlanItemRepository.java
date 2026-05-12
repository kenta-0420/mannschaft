package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 修繕計画項目リポジトリ。
 */
public interface RepairPlanItemRepository extends AbstractTenantAwareRepository<RepairPlanItem, UUID> {

    /** スコープ単位の計画項目を取得（年度昇順）。 */
    List<RepairPlanItem> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
            String scopeType, Long scopeId);

    /** スコープ × ステータスの計画項目を取得。 */
    List<RepairPlanItem> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            String scopeType, Long scopeId, String status);

    /** F09.13 物件 work_package からの逆引き。 */
    List<RepairPlanItem> findByLinkedWorkPackageIdAndDeletedAtIsNull(Long linkedWorkPackageId);

    /**
     * 組織テナント × スコープ × ID で 1 件取得（IDOR 対策）。
     */
    Optional<RepairPlanItem> findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
            UUID id, Long organizationId, String scopeType, Long scopeId);

    /**
     * 任意フィルタ付きのページング検索。年度・カテゴリ・ステータスで絞り込み可能。
     *
     * @param organizationId テナント
     * @param scopeType      スコープ種別
     * @param scopeId        スコープ ID
     * @param plannedYear    対象年度（null で無視）
     * @param category       カテゴリ（null で無視）
     * @param status         ステータス（null で無視）
     * @param pageable       ページング条件
     */
    @Query("""
            SELECT r FROM RepairPlanItem r
             WHERE r.organizationId = :organizationId
               AND r.scopeType = :scopeType
               AND r.scopeId = :scopeId
               AND (:plannedYear IS NULL OR r.plannedYear = :plannedYear)
               AND (:category IS NULL OR r.category = :category)
               AND (:status IS NULL OR r.status = :status)
               AND r.deletedAt IS NULL
            """)
    Page<RepairPlanItem> searchByFilter(
            @Param("organizationId") Long organizationId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("plannedYear") Integer plannedYear,
            @Param("category") String category,
            @Param("status") String status,
            Pageable pageable);
}
