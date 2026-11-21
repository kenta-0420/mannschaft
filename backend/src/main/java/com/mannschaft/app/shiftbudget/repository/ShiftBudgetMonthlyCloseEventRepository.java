package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 締めイベント（(c)）リポジトリ（CMP-260909-2152 確定設計 r12 §3）。
 */
@Repository
public interface ShiftBudgetMonthlyCloseEventRepository extends JpaRepository<ShiftBudgetMonthlyCloseEventEntity, UUID> {

    /** 割当×期間キーの有効イベント（ABORTED 以外）を {@code FOR UPDATE} で取る（Prepare・ensureCloseEvent 用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ShiftBudgetMonthlyCloseEventEntity e WHERE e.allocationId = :allocationId "
            + "AND e.targetMonth = :targetMonth AND e.state <> com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState.ABORTED")
    Optional<ShiftBudgetMonthlyCloseEventEntity> findActiveForUpdate(@Param("allocationId") Long allocationId,
                                                                    @Param("targetMonth") String targetMonth);

    /** イベントを {@code FOR UPDATE} で取る（Commit・Abort 用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ShiftBudgetMonthlyCloseEventEntity e WHERE e.id = :id")
    Optional<ShiftBudgetMonthlyCloseEventEntity> findByIdForUpdate(@Param("id") UUID id);

    /** 割当の指定状態のイベントを共有ロック付きで読む（割当の変更・削除の可否判定。通常 SELECT の古いスナップショットで見落とさない）。 */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT e FROM ShiftBudgetMonthlyCloseEventEntity e WHERE e.allocationId = :allocationId AND e.state IN :states")
    List<ShiftBudgetMonthlyCloseEventEntity> lockByAllocationAndStates(@Param("allocationId") Long allocationId,
                                                                      @Param("states") Collection<ShiftBudgetCloseEventState> states);

    /** イベントの割当 ID（スカラー射影。ロック前の発見用で、管理下エンティティを作らない）。 */
    @Query("SELECT e.allocationId FROM ShiftBudgetMonthlyCloseEventEntity e WHERE e.id = :id")
    Optional<Long> findAllocationIdById(@Param("id") UUID id);

    /** 状態の通常 SELECT（ロックしない参照。割当削除の可否など）。 */
    boolean existsByAllocationIdAndStateIn(Long allocationId, Collection<ShiftBudgetCloseEventState> states);

    /** 指定日を含む期間の、指定状態のイベント（締め済み・PREPARED 判定の current read 用に共有ロックで読む）。 */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT e FROM ShiftBudgetMonthlyCloseEventEntity e, ShiftBudgetClosePeriodEntity p "
            + "WHERE p.id = e.closePeriodId AND e.allocationId = :allocationId AND e.state IN :states "
            + "AND p.periodStart <= :date AND p.periodEnd >= :date")
    List<ShiftBudgetMonthlyCloseEventEntity> findCoveringDateShared(@Param("allocationId") Long allocationId,
                                                                    @Param("date") LocalDate date,
                                                                    @Param("states") Collection<ShiftBudgetCloseEventState> states);

    /** 指定日を含む期間の、指定状態のイベント（ロックなし。勤務枠日付追従の判定用）。 */
    @Query("SELECT e FROM ShiftBudgetMonthlyCloseEventEntity e, ShiftBudgetClosePeriodEntity p "
            + "WHERE p.id = e.closePeriodId AND e.allocationId = :allocationId AND e.state IN :states "
            + "AND p.periodStart <= :date AND p.periodEnd >= :date")
    List<ShiftBudgetMonthlyCloseEventEntity> findCoveringDate(@Param("allocationId") Long allocationId,
                                                              @Param("date") LocalDate date,
                                                              @Param("states") Collection<ShiftBudgetCloseEventState> states);

    /** 割当群の有効イベント（ABORTED 以外）の期間キーと状態（バッチ・追いつきの「締め済み判定」を1クエリで行う）。 */
    @Query("SELECT e.allocationId AS allocationId, e.targetMonth AS targetMonth, e.state AS state "
            + "FROM ShiftBudgetMonthlyCloseEventEntity e WHERE e.allocationId IN :allocationIds "
            + "AND e.state <> com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState.ABORTED")
    List<EventKeyRow> findActiveKeys(@Param("allocationIds") Collection<Long> allocationIds);

    /**
     * 日次バッチの候補割当（CMP-260909-2152 確定設計 r12 §7）。生存かつ開始が昨日以前で、期間終了を覆う CLOSED イベントが無い割当、
     * または PREPARED の割当（再開対象）。組織ID・割当ID昇順。1クエリで抽出する。
     */
    @Query(value = "SELECT a.id FROM shift_budget_allocations a "
            + "WHERE a.deleted_at IS NULL AND a.period_start <= :yesterday "
            + "AND (NOT EXISTS (SELECT 1 FROM shift_budget_monthly_close_events e "
            + "                 JOIN shift_budget_close_periods p ON p.id = e.close_period_id "
            + "                 WHERE e.allocation_id = a.id AND e.state = 'CLOSED' AND p.period_end >= a.period_end) "
            + "     OR EXISTS (SELECT 1 FROM shift_budget_monthly_close_events e2 "
            + "                WHERE e2.allocation_id = a.id AND e2.state = 'PREPARED')) "
            + "ORDER BY a.organization_id, a.id", nativeQuery = true)
    List<Long> findDailyCandidateAllocationIds(@Param("yesterday") LocalDate yesterday);

    /** 有効イベントの射影（allocationId・期間キー・状態）。 */
    interface EventKeyRow {
        Long getAllocationId();

        String getTargetMonth();

        ShiftBudgetCloseEventState getState();
    }
}
