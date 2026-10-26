package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ナレッジベースページリポジトリ。
 */
public interface KbPageRepository extends JpaRepository<KbPageEntity, Long> {

    /**
     * IDで論理削除されていないページを取得する。
     */
    Optional<KbPageEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ内の全ページをパス昇順で取得する（ツリー取得用）。
     */
    List<KbPageEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPathAsc(String scopeType, Long scopeId);

    /**
     * IDで論理削除されていないページの存在確認をする。
     */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
