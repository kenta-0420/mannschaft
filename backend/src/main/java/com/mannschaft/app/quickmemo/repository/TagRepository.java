package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.TagEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 汎用タグマスタリポジトリ。
 */
public interface TagRepository extends JpaRepository<TagEntity, Long> {

    /**
     * IDとスコープでタグを取得する（IDOR防止）。
     */
    Optional<TagEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    /**
     * スコープ内のタグ一覧を使用頻度降順で取得する。
     */
    Page<TagEntity> findByScopeTypeAndScopeIdOrderByUsageCountDesc(String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ内のタグ件数を取得する（50個上限チェック用）。
     */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * スコープ内の同名タグが存在するか確認する（重複チェック用）。
     */
    boolean existsByScopeTypeAndScopeIdAndName(String scopeType, Long scopeId, String name);

    /**
     * スコープ内で指定IDを除く同名タグが存在するか確認する（更新時の重複チェック用）。
     */
    boolean existsByScopeTypeAndScopeIdAndNameAndIdNot(String scopeType, Long scopeId, String name, Long id);

    /**
     * usage_count をインクリメントする（並行安全・原子的更新）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TagEntity t SET t.usageCount = t.usageCount + 1 WHERE t.id = :tagId")
    void incrementUsageCount(@Param("tagId") Long tagId);

    /**
     * usage_count をデクリメントする（負数防止付き・原子的更新）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TagEntity t SET t.usageCount = GREATEST(0, t.usageCount - 1) WHERE t.id = :tagId")
    void decrementUsageCount(@Param("tagId") Long tagId);

    /**
     * usage_count を指定量デクリメントする（物理削除バッチ用・複数メモ集計後に呼び出す）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TagEntity t SET t.usageCount = GREATEST(0, t.usageCount - :delta) WHERE t.id = :tagId")
    void decrementUsageCountBy(@Param("tagId") Long tagId, @Param("delta") Integer delta);

    /**
     * usage_count を直接セットする（整合性バッチ用）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TagEntity t SET t.usageCount = :count WHERE t.id = :id")
    void setUsageCount(@Param("id") Long id, @Param("count") Integer count);

    /**
     * スコープ内の全タグIDを取得する（退会SAGAのPERSONALタグ削除用）。
     */
    @Query("SELECT t.id FROM TagEntity t WHERE t.scopeType = :scopeType AND t.scopeId = :scopeId")
    List<Long> findIdsByScopeTypeAndScopeId(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * 作成者のPERSONALタグをすべて削除する（退会SAGA Step 1用）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TagEntity t WHERE t.scopeType = 'PERSONAL' AND t.scopeId = :userId")
    void deletePersonalTagsByUserId(@Param("userId") Long userId);
}
