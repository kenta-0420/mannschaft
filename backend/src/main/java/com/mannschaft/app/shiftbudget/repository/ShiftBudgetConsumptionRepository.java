package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算消化記録リポジトリ。
 *
 * <p>設計書 F08.7 (v1.2) §5.3 / §11 / §11.1 に準拠。</p>
 */
@Repository
public interface ShiftBudgetConsumptionRepository
        extends JpaRepository<ShiftBudgetConsumptionEntity, Long> {

    /**
     * 同一 (slot_id, user_id, status) の生存レコードを検索する。
     *
     * <p>設計書 §11.1 の (1) 同一 (slot, user) 再 INSERT パターンで利用。
     * 既存 PLANNED を CANCELLED に遷移させる前段の検索に相当。</p>
     */
    Optional<ShiftBudgetConsumptionEntity> findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
            Long slotId, Long userId, ShiftBudgetConsumptionStatus status);

    /**
     * 指定 allocation 配下で指定ステータス群に該当する生存レコードを取得する。
     */
    List<ShiftBudgetConsumptionEntity> findByAllocationIdAndStatusInAndDeletedAtIsNull(
            Long allocationId, Collection<ShiftBudgetConsumptionStatus> statuses);

    /**
     * 指定 allocation 配下で指定ステータス群に該当する生存レコードが存在するかを判定する。
     *
     * <p>設計書 §5.2 HAS_CONSUMPTIONS 制約チェックに対応。
     * {@link ShiftBudgetConsumptionStatus#PLANNED}/{@link ShiftBudgetConsumptionStatus#CONFIRMED}
     * が残っていれば allocation の論理削除を 409 で拒否する判定に使う。</p>
     */
    boolean existsByAllocationIdAndStatusInAndDeletedAtIsNull(
            Long allocationId, Collection<ShiftBudgetConsumptionStatus> statuses);

    /**
     * 指定シフトに紐付く生存消化レコードを全件取得する（シフトキャンセル hook 用）。
     */
    List<ShiftBudgetConsumptionEntity> findByShiftIdAndDeletedAtIsNull(Long shiftId);

    /** 同一シフトを複数経路が取り消す際も、消化PKを同じ順でclaimする。 */
    List<ShiftBudgetConsumptionEntity> findByShiftIdAndDeletedAtIsNullOrderByIdAsc(Long shiftId);

    /**
     * 生存PLANNEDを取消へclaimし、実際に更新できた件数を返す。
     * 一括更新はライフサイクルコールバックを通らないためupdatedAtも更新し、旧managed entityはclearする。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShiftBudgetConsumptionEntity c "
            + "SET c.status = com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CANCELLED, "
            + "c.cancelReason = :reason, c.cancelledAt = :cancelledAt, c.updatedAt = :cancelledAt "
            + "WHERE c.id = :id "
            + "AND c.status = com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED "
            + "AND c.deletedAt IS NULL")
    int cancelPlannedById(@Param("id") Long id, @Param("reason") ShiftBudgetCancelReason reason,
                          @Param("cancelledAt") LocalDateTime cancelledAt);

    /**
     * CMP-260909-1445: 孤児 PLANNED 消化を抱えるシフトを列挙する（整合バッチ用）。
     *
     * <p>「シフトが ARCHIVED もしくは論理削除済みなのに PLANNED 消化が残っている」行が孤児である。
     * 原因系（イベント未発行・リスナー失敗・将来の新経路での書き漏れ）に依らず、状態だけを見て
     * 収束させるための検出クエリ。</p>
     *
     * <p><b>ドメイン越境について</b>: shiftbudget から shift ドメインの {@code shift_schedules} を
     * 直接 JOIN している。同一リポジトリ群の {@code ShiftBudgetRateQueryRepository} が
     * {@code teams} / {@code memberships} を同様に読んでいる前例に倣う。整合検出は
     * 「両ドメインの状態のズレ」そのものを見る処理であり、片側だけを見る実装では表現できない。
     * 参照は read-only で、書き込みは自ドメインの {@code cancelAllForShift} に閉じている。</p>
     *
     * @param limit 1 回の実行で返すシフト件数の上限
     */
    @Query(value =
            "SELECT s.id AS shiftId, s.team_id AS teamId, s.publication_generation AS publicationGeneration "
                    + "FROM shift_budget_consumptions c "
                    + "INNER JOIN shift_schedules s ON s.id = c.shift_id "
                    + "WHERE c.status = 'PLANNED' AND c.deleted_at IS NULL "
                    + "  AND (s.status = 'ARCHIVED' OR s.deleted_at IS NOT NULL) "
                    + "GROUP BY s.id, s.team_id, s.publication_generation "
                    + "ORDER BY s.id "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<OrphanConsumptionShiftRow> findShiftsWithOrphanPlannedConsumptions(@Param("limit") int limit);

    /**
     * 指定シフトの、取消の対象になる PLANNED 消化を、計上先の割当・組織ごとに集計して返す。
     *
     * <p>F01.2.1 AC-G125: 取消（#15）・照合バッチ（#16）は、チームの親組織を再解決せず、
     * 計上時に消化行が紐づけた割当（{@code allocation_id}）の組織を使う。消化行には organization_id の
     * 列が無いため、割当を引いて得る（DDL は変えない）。取消の対象は {@code cancelAllForShift} と同じ
     * PLANNED のみで、CANCELLED / CONFIRMED の古い行は含めない（今回の取消と無関係な組織を拾わない）。
     * 取消<b>より前</b>に呼ぶこと（取消後は PLANNED でなくなり空になる）。</p>
     */
    @Query(value =
            "SELECT a.id AS allocationId, a.organization_id AS organizationId, COUNT(*) AS plannedCount "
                    + "FROM shift_budget_consumptions c "
                    + "INNER JOIN shift_budget_allocations a ON a.id = c.allocation_id "
                    + "WHERE c.shift_id = :shiftId AND c.status = 'PLANNED' AND c.deleted_at IS NULL "
                    + "GROUP BY a.id, a.organization_id "
                    + "ORDER BY a.id",
            nativeQuery = true)
    List<PlannedAllocationRow> findPlannedAllocationRowsByShiftId(@Param("shiftId") Long shiftId);

    /** 世代上限以下の取消候補を、全経路共通の割当ID・消化ID順で一括取得する。 */
    @Query(value = "SELECT c.id AS consumptionId, c.allocation_id AS allocationId, "
            + "a.organization_id AS organizationId, c.amount AS amount "
            + "FROM shift_budget_consumptions c "
            + "INNER JOIN shift_budget_allocations a ON a.id = c.allocation_id "
            + "WHERE c.shift_id = :shiftId AND c.status = 'PLANNED' AND c.deleted_at IS NULL "
            + "AND c.publication_generation <= :generationUpperBound "
            + "ORDER BY c.allocation_id, c.id", nativeQuery = true)
    List<PlannedCancellationRow> findPlannedCancellationRows(
            @Param("shiftId") Long shiftId, @Param("generationUpperBound") long generationUpperBound);

    /** claim前の不変スナップショット。clearAutomaticallyで失効するEntityを持ち回らない。 */
    interface PlannedCancellationRow {
        Long getConsumptionId();

        Long getAllocationId();

        Long getOrganizationId();

        BigDecimal getAmount();
    }

    /** {@link #findPlannedAllocationRowsByShiftId(Long)} の射影。 */
    interface PlannedAllocationRow {
        Long getAllocationId();

        Long getOrganizationId();

        Long getPlannedCount();
    }

    /** {@link #findShiftsWithOrphanPlannedConsumptions(int)} の射影。 */
    interface OrphanConsumptionShiftRow {
        Long getShiftId();

        Long getTeamId();

        Long getPublicationGeneration();
    }
}
