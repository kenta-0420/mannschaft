package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
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
            "SELECT s.id AS shiftId, s.team_id AS teamId "
                    + "FROM shift_budget_consumptions c "
                    + "INNER JOIN shift_schedules s ON s.id = c.shift_id "
                    + "WHERE c.status = 'PLANNED' AND c.deleted_at IS NULL "
                    + "  AND (s.status = 'ARCHIVED' OR s.deleted_at IS NOT NULL) "
                    + "GROUP BY s.id, s.team_id "
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

    // ───────────────────────── CMP-260909-2152 締め期間・3段締め・ロック順統一 ─────────────────────────

    /** 発見（ロック集合の決定にのみ使う通常 SELECT）: 枠×人の生存 PLANNED／CONFIRMED の割当 ID。 */
    @Query("SELECT DISTINCT c.allocationId FROM ShiftBudgetConsumptionEntity c WHERE c.slotId = :slotId AND c.userId = :userId "
            + "AND c.deletedAt IS NULL AND c.status IN (com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED, "
            + "com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED)")
    List<Long> findLiveAllocationIdsBySlotAndUser(@Param("slotId") Long slotId, @Param("userId") Long userId);

    /** 枠×人の生存 PLANNED／CONFIRMED を {@code FOR UPDATE} で再読する（ロック集合の検証・current read）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShiftBudgetConsumptionEntity c WHERE c.slotId = :slotId AND c.userId = :userId "
            + "AND c.deletedAt IS NULL AND c.status IN (com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED, "
            + "com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED) ORDER BY c.id")
    List<ShiftBudgetConsumptionEntity> lockLiveBySlotAndUser(@Param("slotId") Long slotId, @Param("userId") Long userId);

    /** 発見: シフトの生存 PLANNED／CONFIRMED の割当 ID（取消のロック集合の決定のみ）。 */
    @Query("SELECT DISTINCT c.allocationId FROM ShiftBudgetConsumptionEntity c WHERE c.shiftId = :shiftId "
            + "AND c.deletedAt IS NULL AND c.status IN (com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED, "
            + "com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED)")
    List<Long> findLiveAllocationIdsByShift(@Param("shiftId") Long shiftId);

    /** シフトの生存 PLANNED／CONFIRMED を ID 昇順で {@code FOR UPDATE}（取消の割当→消化のロック順）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShiftBudgetConsumptionEntity c WHERE c.shiftId = :shiftId AND c.deletedAt IS NULL "
            + "AND c.status IN (com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED, "
            + "com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED) ORDER BY c.id")
    List<ShiftBudgetConsumptionEntity> lockLiveByShift(@Param("shiftId") Long shiftId);

    /** 割当の、印の無い生存 PLANNED を ID 昇順で {@code FOR UPDATE}（Prepare の勤務枠日付追従・対象選定）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShiftBudgetConsumptionEntity c WHERE c.allocationId = :allocationId AND c.deletedAt IS NULL "
            + "AND c.status = com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED "
            + "AND c.closeEventId IS NULL ORDER BY c.id")
    List<ShiftBudgetConsumptionEntity> lockUnmarkedPlanned(@Param("allocationId") Long allocationId);

    /** 締めイベントの印が付いた生存 PLANNED を {@code FOR UPDATE}（Commit の件数・合計照合と確定）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShiftBudgetConsumptionEntity c WHERE c.closeEventId = :eventId AND c.deletedAt IS NULL "
            + "AND c.status = com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED ORDER BY c.id")
    List<ShiftBudgetConsumptionEntity> lockMarkedPlanned(@Param("eventId") UUID eventId);

    /** 対象行に締めイベントの印を付ける（状態は PLANNED のまま）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShiftBudgetConsumptionEntity c SET c.closeEventId = :eventId WHERE c.id IN :ids")
    int markCloseEvent(@Param("ids") Collection<Long> ids, @Param("eventId") UUID eventId);

    /** Abort: 印を外す。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShiftBudgetConsumptionEntity c SET c.closeEventId = NULL WHERE c.closeEventId = :eventId")
    int clearCloseEvent(@Param("eventId") UUID eventId);

    /** 勤務日の追従（枠の現在日付へ）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShiftBudgetConsumptionEntity c SET c.workDate = :workDate WHERE c.id = :id")
    int updateWorkDate(@Param("id") Long id, @Param("workDate") LocalDate workDate);

    /** 勤務日が指定期間の、印の無い生存 PLANNED の件数（締め対象の見積り・検証）。 */
    @Query("SELECT COUNT(c) FROM ShiftBudgetConsumptionEntity c WHERE c.allocationId = :allocationId AND c.deletedAt IS NULL "
            + "AND c.status = com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED "
            + "AND c.workDate BETWEEN :from AND :to")
    long countPlannedInRange(@Param("allocationId") Long allocationId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 指定の枠群について、生存 PLANNED／CONFIRMED のある (slotId, userId)（未計上候補の除外用。割当を問わない）。 */
    @Query("SELECT c.slotId AS slotId, c.userId AS userId FROM ShiftBudgetConsumptionEntity c WHERE c.slotId IN :slotIds "
            + "AND c.deletedAt IS NULL AND c.status IN (com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.PLANNED, "
            + "com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED)")
    List<SlotUserRow> findLiveSlotUsers(@Param("slotIds") Collection<Long> slotIds);

    // ───────────────────────── 取消履歴の退避（CMP-260909-2152 α 再検分 P2） ─────────────────────────

    /**
     * PLANNED を CANCELLED に遷移する<b>直前</b>に必ず呼ぶ。同じ枠×人の生存 CANCELLED（UNIQUE により高々1行）に
     * {@code deleted_at} を付けて退避し {@code deleted_at_uq} をずらして、新しい CANCELLED が
     * {@code uq_sbc_slot_user_status (slot_id, user_id, status, deleted_at_uq)} に当たらないようにする
     * （CMP-260909-2152 α 再検分 P2。内容の変わる再公開・取消を同じ枠×人で繰り返すと、2回目以降の取消が UNIQUE 違反で
     * TX ごと失敗していた）。
     *
     * <p>履歴は失わない: 行は物理削除も上書きもせず、状態・取消理由・取消時刻・金額・時給・時間をそのまま残す。CANCELLED を読む
     * reader・集計は無い（すべて PLANNED／CONFIRMED に限る）ため見え方は変わらない。</p>
     *
     * <p>退避時刻は DB の格納基準（UTC 壁時計）どおり {@code UTC_TIMESTAMP()} で SQL 側に作る（生 SQL へ Java の壁時計を
     * 束縛しない。CMP-260909-1446）。DATETIME は秒精度なので、同キーの既存の退避時刻と重ならないよう既存の最大＋1秒との
     * 遅い方にする（集計を含む派生表は実体化されるので、更新対象表の自己参照にならない）。呼出側は枠×人の PLANNED を
     * FOR UPDATE で保持しており、同じ枠×人の取消は直列化される。</p>
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE shift_budget_consumptions c "
            + "JOIN (SELECT GREATEST(UTC_TIMESTAMP(), COALESCE(MAX(r.deleted_at) + INTERVAL 1 SECOND, UTC_TIMESTAMP())) AS retired "
            + "      FROM shift_budget_consumptions r WHERE r.slot_id = :slotId AND r.user_id = :userId "
            + "      AND r.status = 'CANCELLED' AND r.deleted_at IS NOT NULL) m "
            + "SET c.deleted_at = m.retired "
            + "WHERE c.slot_id = :slotId AND c.user_id = :userId AND c.status = 'CANCELLED' AND c.deleted_at IS NULL",
            nativeQuery = true)
    int retireLiveCancelledBeforeCancel(@Param("slotId") Long slotId, @Param("userId") Long userId);

    /** {@link #findLiveSlotUsers(Collection)} の射影。 */
    interface SlotUserRow {
        Long getSlotId();

        Long getUserId();
    }

    /**
     * CMP-260909-2152: 指定シフトの生存消化（PLANNED／CONFIRMED）を、計上先の割当・組織ごとに集計して返す。
     * 締め後の CONFIRMED だけが残るシフトの取消（失敗イベント CANCEL_AFTER_CLOSE）も取消入口へ届けるため、PLANNED に限らない。
     * {@code plannedCount} は PLANNED の件数。
     */
    @Query(value =
            "SELECT a.id AS allocationId, a.organization_id AS organizationId, "
                    + "SUM(CASE WHEN c.status = 'PLANNED' THEN 1 ELSE 0 END) AS plannedCount "
                    + "FROM shift_budget_consumptions c "
                    + "INNER JOIN shift_budget_allocations a ON a.id = c.allocation_id "
                    + "WHERE c.shift_id = :shiftId AND c.status IN ('PLANNED', 'CONFIRMED') AND c.deleted_at IS NULL "
                    + "GROUP BY a.id, a.organization_id "
                    + "ORDER BY a.id",
            nativeQuery = true)
    List<PlannedAllocationRow> findLiveAllocationRowsByShiftId(@Param("shiftId") Long shiftId);

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
    }
}
