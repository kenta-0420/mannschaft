package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbPagePinEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ナレッジベースページピン留めリポジトリ。
 */
public interface KbPagePinRepository extends JpaRepository<KbPagePinEntity, Long> {

    /**
     * スコープ内のピン留めをsort_order昇順で取得する。
     */
    List<KbPagePinEntity> findByScopeTypeAndScopeIdOrderBySortOrderAsc(String scopeType, Long scopeId);

    /**
     * ページID・スコープ種別・スコープIDでピン留めを取得する。
     */
    Optional<KbPagePinEntity> findByKbPageIdAndScopeTypeAndScopeId(Long kbPageId, String scopeType, Long scopeId);

    /**
     * スコープ内のピン留め件数をカウントする。
     */
    int countByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
