package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 順番待ちカテゴリリポジトリ。
 */
public interface QueueCategoryRepository extends JpaRepository<QueueCategoryEntity, Long> {

    /**
     * スコープ指定でカテゴリ一覧を表示順で取得する。
     */
    List<QueueCategoryEntity> findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
            QueueScopeType scopeType, Long scopeId);

    /**
     * IDとスコープでカテゴリを取得する。
     */
    Optional<QueueCategoryEntity> findByIdAndScopeTypeAndScopeId(
            Long id, QueueScopeType scopeType, Long scopeId);
}
