package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.TodoBudgetLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 TODO/プロジェクト 予算紐付リポジトリ（Phase 9-γ）。
 *
 * <p>設計書 F08.7 (v1.2) §5.4 / §6.2.4 / §11 に対応するクエリを定義する。</p>
 */
@Repository
public interface TodoBudgetLinkRepository extends JpaRepository<TodoBudgetLinkEntity, Long> {

    /**
     * 同一 (project_id, allocation_id) の紐付重複チェック用。
     */
    Optional<TodoBudgetLinkEntity> findByProjectIdAndAllocationId(Long projectId, Long allocationId);

    /**
     * 同一 (todo_id, allocation_id) の紐付重複チェック用。
     */
    Optional<TodoBudgetLinkEntity> findByTodoIdAndAllocationId(Long todoId, Long allocationId);

    /**
     * プロジェクト配下の全紐付を取得する（プロジェクト編集画面の予算タブ用）。
     */
    List<TodoBudgetLinkEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * TODO 配下の全紐付を取得する（TODO 詳細画面の予算情報表示用）。
     */
    List<TodoBudgetLinkEntity> findByTodoIdOrderByCreatedAtDesc(Long todoId);

    /**
     * 指定割当に紐付いている全リンクを取得する（割当削除前の検証等）。
     */
    List<TodoBudgetLinkEntity> findByAllocationIdOrderByCreatedAtDesc(Long allocationId);

    /**
     * 多テナント検証付き取得: 紐付の allocation が指定組織に属することを保証する。
     *
     * <p>IDOR 対策。{@link com.mannschaft.app.shiftbudget.service.TodoBudgetLinkService}
     * の DELETE 処理から呼び出される。</p>
     */
    @Query("SELECT l FROM TodoBudgetLinkEntity l "
            + "JOIN ShiftBudgetAllocationEntity a ON a.id = l.allocationId "
            + "WHERE l.id = :linkId AND a.organizationId = :organizationId "
            + "  AND a.deletedAt IS NULL")
    Optional<TodoBudgetLinkEntity> findByIdAndOrganizationId(
            @Param("linkId") Long linkId,
            @Param("organizationId") Long organizationId);

    /**
     * 集計用: 指定プロジェクトに紐付くリンクのうち、指定組織所属の生存割当に紐付くもののみ取得。
     *
     * <p>多テナント分離を保証する。{@code TODO 経由} の集計 SQL 構築時に補助的に使う想定。</p>
     */
    @Query("SELECT l FROM TodoBudgetLinkEntity l "
            + "JOIN ShiftBudgetAllocationEntity a ON a.id = l.allocationId "
            + "WHERE l.projectId = :projectId AND a.organizationId = :organizationId "
            + "  AND a.deletedAt IS NULL")
    List<TodoBudgetLinkEntity> findByProjectIdAndOrganizationId(
            @Param("projectId") Long projectId,
            @Param("organizationId") Long organizationId);

    /**
     * プロジェクト消化額（直接経路）を集計する。
     *
     * <p>設計書 §4.3 経路1: {@code shift_schedules.linked_project_id = :projectId} の
     * シフトに紐付く全 PLANNED/CONFIRMED 消化を SUM。多テナント分離のため
     * {@code shift_budget_allocations.organization_id} で組織を絞り込む。</p>
     *
     * @return 直接経路の消化額合計（消化なしなら 0）
     */
    @Query(value =
            "SELECT COALESCE(SUM(c.amount), 0) "
                    + "FROM shift_budget_consumptions c "
                    + "INNER JOIN shift_schedules s ON c.shift_id = s.id "
                    + "INNER JOIN shift_budget_allocations a ON c.allocation_id = a.id "
                    + "WHERE s.linked_project_id = :projectId "
                    + "  AND c.status IN ('PLANNED', 'CONFIRMED') "
                    + "  AND c.deleted_at IS NULL "
                    + "  AND a.organization_id = :organizationId",
            nativeQuery = true)
    BigDecimal sumDirectAmountForProject(
            @Param("projectId") Long projectId,
            @Param("organizationId") Long organizationId);

    /**
     * プロジェクト消化額（TODO 経由）を集計する。
     *
     * <p>設計書 §4.3 経路2: {@code todo_budget_links} 経由で当該プロジェクトに紐付く
     * 消化を SUM。link_amount があれば {@code LEAST(消化, 上限)} で按分、
     * link_percentage があれば {@code amount * percentage / 100}、両方 NULL なら全額。</p>
     *
     * <p><strong>重複防止</strong>: 直接経路（{@code shift_schedules.linked_project_id = :projectId}）と
     * 重複する分は除外する（{@code s.linked_project_id IS NULL OR s.linked_project_id != :projectId}）。</p>
     *
     * @return TODO 経由の消化額合計（消化なしなら 0）
     */
    @Query(value =
            "SELECT COALESCE(SUM( "
                    + "  CASE "
                    + "    WHEN tbl.link_amount IS NOT NULL THEN LEAST(c.amount, tbl.link_amount) "
                    + "    WHEN tbl.link_percentage IS NOT NULL THEN c.amount * tbl.link_percentage / 100 "
                    + "    ELSE c.amount "
                    + "  END "
                    + "), 0) "
                    + "FROM todo_budget_links tbl "
                    + "INNER JOIN shift_budget_consumptions c ON c.allocation_id = tbl.allocation_id "
                    + "INNER JOIN shift_budget_allocations a ON c.allocation_id = a.id "
                    + "LEFT JOIN shift_schedules s ON c.shift_id = s.id "
                    + "WHERE tbl.project_id = :projectId "
                    + "  AND c.status IN ('PLANNED', 'CONFIRMED') "
                    + "  AND c.deleted_at IS NULL "
                    + "  AND a.organization_id = :organizationId "
                    + "  AND (s.linked_project_id IS NULL OR s.linked_project_id != :projectId)",
            nativeQuery = true)
    BigDecimal sumViaTodoAmountForProject(
            @Param("projectId") Long projectId,
            @Param("organizationId") Long organizationId);
}
