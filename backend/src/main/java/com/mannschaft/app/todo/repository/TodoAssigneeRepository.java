package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * TODO担当者リポジトリ。
 */
public interface TodoAssigneeRepository extends JpaRepository<TodoAssigneeEntity, Long> {

    /**
     * TODO内の担当者一覧を取得する。
     */
    List<TodoAssigneeEntity> findByTodoId(Long todoId);

    /**
     * 複数TODOの担当者を一括取得する（一覧変換の N+1 回避用）。
     * 並びは担当者ID昇順で、{@link #findByTodoId(Long)} の PK 順と一致させる。
     * 呼び出し側で空コレクションを渡さないこと。
     */
    List<TodoAssigneeEntity> findByTodoIdInOrderByIdAsc(java.util.Collection<Long> todoIds);

    /**
     * TODO内の特定ユーザーの割り当てを取得する。
     */
    Optional<TodoAssigneeEntity> findByTodoIdAndUserId(Long todoId, Long userId);

    /**
     * TODO内の特定ユーザーの割り当て存在チェック。
     */
    boolean existsByTodoIdAndUserId(Long todoId, Long userId);

    /**
     * TODO内の担当者数を取得する。
     */
    long countByTodoId(Long todoId);
}
