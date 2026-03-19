package com.mannschaft.app.todo;

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
