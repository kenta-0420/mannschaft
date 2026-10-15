package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.TodoPersonalMemoEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

/**
 * TODO個人メモリポジトリ。
 * 論理削除なし（物理削除のみ）。1TODO×1ユーザー=1レコード（UPSERT運用）。
 */
public interface TodoPersonalMemoRepository extends JpaRepository<TodoPersonalMemoEntity, Long> {

    /**
     * TODO IDとユーザーIDで個人メモを取得する。
     */
    Optional<TodoPersonalMemoEntity> findByTodoIdAndUserId(Long todoId, Long userId);

    /**
     * TODO IDとユーザーIDで個人メモを物理削除する。
     */
    @Transactional
    @Modifying
    void deleteByTodoIdAndUserId(Long todoId, Long userId);
}
