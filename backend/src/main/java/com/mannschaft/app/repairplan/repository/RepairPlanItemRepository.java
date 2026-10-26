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

    /**
     * スコープ単位で計画年度別の修繕費見積合計を集計する（シミュレーション入力用）。
     *
     * <p>返却値は {@code Object[]{Integer plannedYear, BigDecimal sum}} の形式。
     * Service 層で {@code Map<Integer, BigDecimal>} に変換して使用する。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @return [plannedYear, sumEstimatedAmount] のリスト（年度昇順）
     */
    @Query("SELECT r.plannedYear, CAST(SUM(r.estimatedAmount) AS java.math.BigDecimal) " +
           "FROM RepairPlanItem r " +
           "WHERE r.scopeType = :scopeType AND r.scopeId = :scopeId AND r.deletedAt IS NULL " +
           "GROUP BY r.plannedYear " +
           "ORDER BY r.plannedYear ASC")
    List<Object[]> sumEstimatedAmountByYear(@Param("scopeType") String scopeType,
                                             @Param("scopeId") Long scopeId);

    /**
     * 地層タイムライン用: 年度×カテゴリ別修繕費合計集計。
     *
     * <p>返却値は {@code Object[]{Integer plannedYear, String category, Long sumAmount, String minutesNotes}}
     * の形式。minutesNotes は該当年度・カテゴリの minutes_note を '|' 区切りで連結したもの。
     * JPQL では {@code GROUP_CONCAT} が標準外のため nativeQuery=true で実装する。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID（BIGINT）
     * @param yearFrom  集計開始年度（含む）
     * @param yearTo    集計終了年度（含む）
     */
    @Query(nativeQuery = true, value = """
            SELECT r.planned_year, r.category,
                   CAST(SUM(r.estimated_amount) AS SIGNED),
                   GROUP_CONCAT(r.minutes_note SEPARATOR '|')
            FROM repair_plan_items r
            WHERE r.scope_type = :scopeType
              AND r.scope_id = :scopeId
              AND r.planned_year >= :yearFrom
              AND r.planned_year <= :yearTo
              AND r.deleted_at IS NULL
            GROUP BY r.planned_year, r.category
            ORDER BY r.planned_year ASC, r.category ASC
            """)
    List<Object[]> aggregateByYearAndCategory(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("yearFrom") int yearFrom,
            @Param("yearTo") int yearTo);
}
