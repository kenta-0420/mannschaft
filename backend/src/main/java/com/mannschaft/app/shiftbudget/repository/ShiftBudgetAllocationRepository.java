package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算割当リポジトリ。
 *
 * <p>設計書 F08.7 (v1.2) §5.2 / §9.5 / §11.1 に準拠。</p>
 *
 * <p>マスター御裁可 Q4 確定方針:</p>
 * <ul>
 *   <li>{@code consumed_amount} の競合制御はアトミック増減
 *       （{@link #incrementConsumedAmount}/{@link #decrementConsumedAmount}）のみ。</li>
 *   <li>{@code @Version} 楽観ロックは allocation 自体の編集（割当額変更等）に併用するが、
 *       消化集計（{@code consumed_amount}）の競合制御には使わない（過剰な再試行を回避）。</li>
 * </ul>
 *
 * <p>Phase 9-δ で {@code incrementConfirmedAmount}/{@code decrementConfirmedAmount}
 * を追加予定（本 Phase 9-β スコープ外）。</p>
 */
@Repository
public interface ShiftBudgetAllocationRepository
        extends JpaRepository<ShiftBudgetAllocationEntity, Long> {

    /**
     * 多テナント検索の基本: 組織IDと割当IDで生存レコードを取得する。
     */
    Optional<ShiftBudgetAllocationEntity> findByIdAndOrganizationIdAndDeletedAtIsNull(
            Long id, Long organizationId);

    /**
     * 組織配下の生存割当一覧を期間降順で取得する。
     */
    List<ShiftBudgetAllocationEntity> findByOrganizationIdAndDeletedAtIsNullOrderByPeriodStartDesc(
            Long organizationId, Pageable pageable);

    /**
     * 同一スコープ（org × team × project × category × period）の生存割当を
     * {@code SELECT ... FOR UPDATE} で先取りロックする（重複作成チェック用）。
     *
     * <p>設計書 §5.2 「作成・更新時の生存重複チェック」運用ルールに対応。
     * 同時 INSERT 競合を排除するため呼出側はトランザクション内で本メソッドを使うこと。</p>
     *
     * <p>{@code teamId}/{@code projectId} は NULL 許容。NULL 一致のため {@code IS NULL} で評価する。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a "
            + "WHERE a.organizationId = :organizationId "
            + "  AND ((:teamId IS NULL AND a.teamId IS NULL) OR a.teamId = :teamId) "
            + "  AND ((:projectId IS NULL AND a.projectId IS NULL) OR a.projectId = :projectId) "
            + "  AND a.budgetCategoryId = :budgetCategoryId "
            + "  AND a.periodStart = :periodStart "
            + "  AND a.periodEnd = :periodEnd "
            + "  AND a.deletedAt IS NULL")
    Optional<ShiftBudgetAllocationEntity> findLiveByScope(
            @Param("organizationId") Long organizationId,
            @Param("teamId") Long teamId,
            @Param("projectId") Long projectId,
            @Param("budgetCategoryId") Long budgetCategoryId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);

    /**
     * 指定日が含まれる生存割当を取得する（hook 用: シフト公開時に該当月の allocation を解決する）。
     *
     * <p>{@code teamId} は NULL 許容（組織全体の割当も許可）。</p>
     */
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a "
            + "WHERE a.organizationId = :organizationId "
            + "  AND ((:teamId IS NULL AND a.teamId IS NULL) OR a.teamId = :teamId) "
            + "  AND a.periodStart <= :date "
            + "  AND a.periodEnd >= :date "
            + "  AND a.deletedAt IS NULL")
    Optional<ShiftBudgetAllocationEntity> findContainingPeriod(
            @Param("organizationId") Long organizationId,
            @Param("teamId") Long teamId,
            @Param("date") LocalDate date);

    /**
     * {@code consumed_amount} をアトミックに加算する（マスター御裁可 Q4 採用方針）。
     *
     * <p>{@code @Version} を介さない直接 UPDATE のため、楽観ロック競合を発生させずに
     * 並行加算が可能。設計書 §11.1 の (4) 「allocation.consumed_amount に加算」に対応。</p>
     *
     * @return 更新行数（呼出側で 1 件であることを assert することを推奨）
     */
    @Modifying
    @Query("UPDATE ShiftBudgetAllocationEntity a "
            + "SET a.consumedAmount = a.consumedAmount + :delta "
            + "WHERE a.id = :allocationId")
    int incrementConsumedAmount(@Param("allocationId") Long allocationId,
                                @Param("delta") BigDecimal delta);

    /**
     * {@code consumed_amount} をアトミックに減算する。
     *
     * <p>設計書 §11.1 の (1) 「allocation.consumed_amount から差し引き」に対応。
     * CHECK 制約 {@code chk_sba_consumed} により負数は許可されない（DB レベルで担保）。</p>
     *
     * @return 更新行数
     */
    @Modifying
    @Query("UPDATE ShiftBudgetAllocationEntity a "
            + "SET a.consumedAmount = a.consumedAmount - :delta "
            + "WHERE a.id = :allocationId")
    int decrementConsumedAmount(@Param("allocationId") Long allocationId,
                                @Param("delta") BigDecimal delta);
}
