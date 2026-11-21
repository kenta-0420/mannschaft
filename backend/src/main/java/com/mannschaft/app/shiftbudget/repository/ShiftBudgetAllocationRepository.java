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
     * 同一チームに対し、<b>別の組織</b>が持つ期間の重なる生存割当を排他ロック付きで取得する
     * （F01.2.1 AC-N10 / AC-N15。重なり判定は閉区間 {@code [start, end]} 同士）。
     *
     * <p>チームは複数の組織に加盟しうるため、予算の計上先を一意に保つには「同じチーム・重なる期間に
     * 複数の組織が割当を持たない」ことを作成時に保証する必要がある。同一組織内の重複は
     * {@link #findLiveByScope} と関数インデックスが担う。</p>
     *
     * <p><b>ロック付きで読む理由</b>: MySQL の REPEATABLE READ では、同一トランザクション内で先に
     * 通常の SELECT（機能フラグ・権限の確認）をしていると、以降の通常 SELECT は古いスナップショットを
     * 読み、先に COMMIT した他組織の割当を見落とす。ロック付き読み取りは常に最新のコミット済み行を読む
     * ので、チーム行のロック取得後にここで判定すれば競合を確実に検出できる。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a "
            + "WHERE a.teamId = :teamId "
            + "  AND a.organizationId <> :organizationId "
            + "  AND a.periodStart <= :periodEnd "
            + "  AND a.periodEnd >= :periodStart "
            + "  AND a.deletedAt IS NULL")
    List<ShiftBudgetAllocationEntity> findLiveOverlappingByOtherOrganizations(
            @Param("teamId") Long teamId,
            @Param("organizationId") Long organizationId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);

    /**
     * 指定日を含む、チーム個別の生存割当を持つ組織のうち、チームが ACTIVE 加盟している組織を返す
     * （シフト公開の計上先の解決用。F01.2.1 §9.2 #14）。
     *
     * <p>作成時に「重なる期間は 1 組織のみ」を保証しているため通常は 0 または 1 件。
     * 昇順で決定的に返す。</p>
     */
    @Query(value =
            "SELECT DISTINCT a.organization_id FROM shift_budget_allocations a "
                    + "INNER JOIN team_org_memberships tom "
                    + "   ON tom.team_id = a.team_id AND tom.organization_id = a.organization_id "
                    + "  AND tom.status = 'ACTIVE' "
                    + "WHERE a.team_id = :teamId "
                    + "  AND a.period_start <= :date AND a.period_end >= :date "
                    + "  AND a.deleted_at IS NULL "
                    + "ORDER BY a.organization_id",
            nativeQuery = true)
    List<Long> findOrganizationIdsWithTeamAllocationContaining(
            @Param("teamId") Long teamId, @Param("date") LocalDate date);

    /**
     * 指定日を含む、組織全体割当（{@code team_id IS NULL}）を持つ組織のうち、チームが ACTIVE 加盟している
     * 組織を返す（チーム個別の割当が無いときのフォールバック用）。昇順で決定的に返す。
     */
    @Query(value =
            "SELECT DISTINCT a.organization_id FROM shift_budget_allocations a "
                    + "INNER JOIN team_org_memberships tom "
                    + "   ON tom.organization_id = a.organization_id AND tom.status = 'ACTIVE' "
                    + "WHERE tom.team_id = :teamId AND a.team_id IS NULL "
                    + "  AND a.period_start <= :date AND a.period_end >= :date "
                    + "  AND a.deleted_at IS NULL "
                    + "ORDER BY a.organization_id",
            nativeQuery = true)
    List<Long> findOrganizationIdsWithOrgWideAllocationContaining(
            @Param("teamId") Long teamId, @Param("date") LocalDate date);

    /**
     * 閉区間 {@code [from, to]} に重なる予算割当候補を一括で返す（CMP-261115-1821 公開時給ガードの公開 TX 外の取得）。
     *
     * <p>{@link #findOrganizationIdsWithTeamAllocationContaining}（チーム個別）と
     * {@link #findOrganizationIdsWithOrgWideAllocationContaining}（組織全体）の和集合を、日付ごとではなく期間で
     * 1 回に引く。いずれもチームが ACTIVE 加盟している組織の生存割当に限る。日付ごとの採否（チーム個別優先・
     * 組織全体へのフォールバック）は呼び出し側が値で判定する。</p>
     *
     * <p>列は {@code organization_id, team_id(NULL=組織全体), period_start, period_end}。ネイティブのスカラー投影の
     * DATE は JDBC 既定型（{@code java.sql.Date}）で戻りうるため、呼び出し側で {@code LocalDate} へ変換する。</p>
     */
    @Query(value =
            "SELECT a.organization_id, a.team_id, a.period_start, a.period_end "
                    + "FROM shift_budget_allocations a "
                    + "INNER JOIN team_org_memberships tom "
                    + "   ON tom.organization_id = a.organization_id AND tom.team_id = :teamId "
                    + "  AND tom.status = 'ACTIVE' "
                    + "WHERE (a.team_id = :teamId OR a.team_id IS NULL) "
                    + "  AND a.period_start <= :to AND a.period_end >= :from "
                    + "  AND a.deleted_at IS NULL "
                    + "ORDER BY a.organization_id, a.period_start, a.id",
            nativeQuery = true)
    List<Object[]> findPublicationCandidatesOverlapping(
            @Param("teamId") Long teamId, @Param("from") LocalDate from, @Param("to") LocalDate to);

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

    /**
     * {@code confirmed_amount} をアトミックに加算する（Phase 9-δ 追加）。
     *
     * <p>月次締め ({@code MonthlyShiftBudgetCloseService}) で
     * PLANNED → CONFIRMED 遷移後に確定額を加算する用途。
     * 設計書 §4.6 「PLANNED を CONFIRMED に一括昇格」に対応。</p>
     *
     * @return 更新行数
     */
    @Modifying
    @Query("UPDATE ShiftBudgetAllocationEntity a "
            + "SET a.confirmedAmount = a.confirmedAmount + :delta "
            + "WHERE a.id = :allocationId")
    int incrementConfirmedAmount(@Param("allocationId") Long allocationId,
                                 @Param("delta") BigDecimal delta);

    /**
     * 月次締めのために割当 1 件を<b>排他ロック付きで</b>取得する（CMP-260910-1556）。
     *
     * <p>{@code MonthlyShiftBudgetCloseService#closeOneAllocation} は
     * 「月次仕訳が未作成であること」を確認してから PLANNED 消化を CONFIRMED 化し
     * {@code confirmed_amount} を加算する。この確認と更新の間に他トランザクションが
     * 割り込むと、両者とも「未締め」と判定して<b>同じ PLANNED 消化を二重に計上</b>する
     * （手動 API の同時送信、手動 API と cron の重なりで実際に起こりうる）。
     * {@code budget_transactions} には
     * {@code (source_type, source_id, transaction_date)} の一意制約が無いため、
     * 仕訳も 2 件入って会計金額が倍になる。</p>
     *
     * <p>本メソッドで割当行を {@code SELECT ... FOR UPDATE} 相当でロックし、
     * 以降の確認・更新をその保護下で行うことで直列化する。</p>
     *
     * @param allocationId 割当 ID
     * @return ロック取得済みの割当（存在しない場合は empty）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a WHERE a.id = :allocationId")
    Optional<ShiftBudgetAllocationEntity> findByIdForUpdate(@Param("allocationId") Long allocationId);

    /**
     * 組織の、開始が指定日以前の生存割当（CMP-260909-2152 締めの追いつき・手動締めの対象）。終了済みキーの絞り込みは呼び出し側が
     * 期間表で行う。
     */
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a WHERE a.organizationId = :organizationId "
            + "AND a.periodStart <= :date AND a.deletedAt IS NULL ORDER BY a.id")
    List<ShiftBudgetAllocationEntity> findLiveByOrgStartingOnOrBefore(@Param("organizationId") Long organizationId,
                                                                      @Param("date") LocalDate date);

    /** 組織の生存割当のうち、期間が [from, to] と重なるもの（締め期間と重なる割当・期間一覧）。 */
    @Query("SELECT a FROM ShiftBudgetAllocationEntity a WHERE a.organizationId = :organizationId "
            + "AND a.periodStart <= :to AND a.periodEnd >= :from AND a.deletedAt IS NULL ORDER BY a.id")
    List<ShiftBudgetAllocationEntity> findLiveByOrgOverlapping(@Param("organizationId") Long organizationId,
                                                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * CMP-260909-2152: 割当の PREPARED／CLOSED の締めイベントを共有ロック付きで読む（割当の変更・削除の可否判定）。
     * 通常 SELECT の古いスナップショットで見落とさない。割当ドメインの Repository から自ドメインの表を読むだけ
     * （Service のコンストラクタを変えない）。
     */
    @Query(value = "SELECT e.id FROM shift_budget_monthly_close_events e WHERE e.allocation_id = :allocationId "
            + "AND e.state IN ('PREPARED', 'CLOSED') FOR SHARE", nativeQuery = true)
    List<byte[]> lockLiveCloseEventIds(@Param("allocationId") Long allocationId);
}
