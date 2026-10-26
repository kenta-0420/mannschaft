package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.TodoCommentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * TODOコメントリポジトリ。
 */
public interface TodoCommentRepository extends JpaRepository<TodoCommentEntity, Long> {

    /**
     * TODO内のコメント一覧を取得する（時系列順）。
     */
    Page<TodoCommentEntity> findByTodoIdOrderByCreatedAtAsc(Long todoId, Pageable pageable);

    /**
     * コメントIDとTodo IDで取得する。
     */
    Optional<TodoCommentEntity> findByIdAndTodoId(Long id, Long todoId);
}
