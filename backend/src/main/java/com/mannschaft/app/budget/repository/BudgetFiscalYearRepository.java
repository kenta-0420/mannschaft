package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 予算年度リポジトリ。
 */
public interface BudgetFiscalYearRepository extends JpaRepository<BudgetFiscalYearEntity, Long> {

    /**
     * スコープとステータスで年度を検索する。
     */
    List<BudgetFiscalYearEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, BudgetFiscalYearStatus status);

    /**
     * スコープで年度を検索する。
     */
    List<BudgetFiscalYearEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * スコープと年度名で年度を検索する。
     */
    Optional<BudgetFiscalYearEntity> findByScopeTypeAndScopeIdAndName(String scopeType, Long scopeId, String name);

    /**
     * スコープで期間が重複する年度が存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndStartDateLessThanAndEndDateGreaterThan(String scopeType, Long scopeId, LocalDate endDate, LocalDate startDate);

    /**
     * F10.1.1 P3b Wave3: 指定スコープで「現年度」（today を期間に含む年度）を返す。
     *
     * <p>条件は {@code start_date <= today AND end_date >= today}（{@code deleted_at IS NULL} は
     * Entity の {@code @SQLRestriction} で担保）。複数該当時は {@code start_date} 降順で並べ、
     * 呼び出し側が先頭 1 件を採る。WHERE に scope を含むため IDOR は構造的に発生しない。</p>
     *
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID
     * @param today     基準日（JST の本日）
     * @return today を期間に含む年度（start_date 降順）。0 件なら空リスト
     */
    @Query("SELECT fy FROM BudgetFiscalYearEntity fy "
            + "WHERE fy.scopeType = :scopeType AND fy.scopeId = :scopeId "
            + "AND fy.startDate <= :today AND fy.endDate >= :today "
            + "ORDER BY fy.startDate DESC")
    List<BudgetFiscalYearEntity> findCurrentByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("today") LocalDate today);
}
