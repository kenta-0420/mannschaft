package com.mannschaft.app.todo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * TODOリポジトリ。
 */
public interface TodoRepository extends JpaRepository<TodoEntity, Long> {

    /**
     * IDで論理削除されていないTODOを取得する。
     */
    Optional<TodoEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ別のTODO一覧を取得する（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別・ステータス別のTODO一覧を取得する（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, TodoStatus status, Pageable pageable);

    /**
     * プロジェクト内のTODO一覧を取得する（論理削除除外）。
     */
    List<TodoEntity> findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(Long projectId);

    /**
     * マイルストーン内のTODO数を取得する（論理削除除外）。
     */
    long countByMilestoneIdAndDeletedAtIsNull(Long milestoneId);

    /**
     * マイルストーン内の完了済みTODO数を取得する（論理削除除外）。
     */
    long countByMilestoneIdAndStatusAndDeletedAtIsNull(Long milestoneId, TodoStatus status);

    /**
     * プロジェクトに紐付かないTODOのうちプロジェクト内のもの（milestone_id = NULL）の数を取得する。
     */
    long countByProjectIdAndMilestoneIdIsNullAndDeletedAtIsNull(Long projectId);

    /**
     * プロジェクトに紐付かないTODOのうち完了済みの数を取得する。
     */
    long countByProjectIdAndMilestoneIdIsNullAndStatusAndDeletedAtIsNull(Long projectId, TodoStatus status);

    /**
     * 複数IDで論理削除されていないTODOを取得する（一括ステータス変更用）。
     */
    List<TodoEntity> findByIdInAndDeletedAtIsNull(List<Long> ids);

    /**
     * ユーザーに割り当てられた全TODOを取得する（全スコープ横断）。
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.id IN (SELECT ta.todoId FROM TodoAssigneeEntity ta WHERE ta.userId = :userId)
            ORDER BY t.dueDate ASC NULLS LAST, t.priority DESC
            """)
    List<TodoEntity> findMyTodos(@Param("userId") Long userId);
}
