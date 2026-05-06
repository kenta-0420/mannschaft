package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.TodoHandoffEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * TODO キャッチボール履歴リポジトリ（F02.3.1 Phase 2）。
 */
@Repository
public interface TodoHandoffRepository extends JpaRepository<TodoHandoffEntity, Long> {

    /**
     * 指定 TODO の履歴を新しい順で取得する。
     *
     * @param todoId 対象 TODO ID
     * @return 履歴一覧（新しい順）
     */
    List<TodoHandoffEntity> findByTodoIdOrderByCreatedAtDesc(Long todoId);
}
