package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算 閾値超過警告リポジトリ。
 *
 * <p>設計書 F08.7 (v1.2) §5.5 / §6.2.5 に準拠。</p>
 *
 * <p>主用途:</p>
 * <ul>
 *   <li>同一 (allocation, threshold) ペアの重複発火防止 ({@link #findByAllocationIdAndThresholdPercent})</li>
 *   <li>未承認警告の組織スコープ絞り込み ({@link #findUnacknowledgedByOrg})</li>
 * </ul>
 */
@Repository
public interface BudgetThresholdAlertRepository
        extends JpaRepository<BudgetThresholdAlertEntity, Long> {

    /**
     * 同一 (allocation, threshold) ペアでの既存警告を検索する。
     *
     * <p>UNIQUE (allocation_id, threshold_percent) 制約により最大 1 件。
     * 警告発火時の重複防止に使用する（既存ありなら追加 INSERT を抑止）。</p>
     */
    Optional<BudgetThresholdAlertEntity> findByAllocationIdAndThresholdPercent(
            Long allocationId, Integer thresholdPercent);

    /**
     * 組織スコープで未承認警告（{@code acknowledged_at IS NULL}）を取得する。
     *
     * <p>設計書 §6.2.5 / API #9 に対応する一覧クエリ。
     * {@code shift_budget_allocations} を JOIN して {@code organization_id} で絞り込む。
     * 並び順は {@code triggered_at DESC}（新しい警告ほど上）。</p>
     */
    @Query("SELECT a FROM BudgetThresholdAlertEntity a "
            + "WHERE a.acknowledgedAt IS NULL "
            + "  AND a.allocationId IN ("
            + "      SELECT alloc.id FROM ShiftBudgetAllocationEntity alloc "
            + "      WHERE alloc.organizationId = :organizationId "
            + "        AND alloc.deletedAt IS NULL"
            + "  ) "
            + "ORDER BY a.triggeredAt DESC")
    List<BudgetThresholdAlertEntity> findUnacknowledgedByOrg(
            @Param("organizationId") Long organizationId,
            Pageable pageable);
}
