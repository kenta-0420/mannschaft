package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.TodoSharedMemoEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TODO共有メモエントリリポジトリ。
 * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みエントリは自動除外される。
 */
public interface TodoSharedMemoEntryRepository extends JpaRepository<TodoSharedMemoEntryEntity, Long> {

    /**
     * TODO内のメモを時系列（createdAt昇順）でページネーション取得する。
     * 論理削除済みエントリは@SQLRestrictionで自動除外。
     */
    Page<TodoSharedMemoEntryEntity> findByTodoIdOrderByCreatedAtAsc(Long todoId, Pageable pageable);

    /**
     * TODO内のメモ件数を取得する（500件上限チェック用）。
     * 論理削除済みエントリは@SQLRestrictionで自動除外。
     */
    long countByTodoId(Long todoId);
}
