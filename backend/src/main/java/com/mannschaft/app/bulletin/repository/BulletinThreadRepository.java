package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 掲示板スレッドリポジトリ。
 */
public interface BulletinThreadRepository extends JpaRepository<BulletinThreadEntity, Long> {

    String SEARCH_QUERY = "SELECT * FROM bulletin_threads WHERE deleted_at IS NULL AND scope_type = :scopeType AND scope_id = :scopeId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE)";
    String SEARCH_COUNT_QUERY = "SELECT COUNT(*) FROM bulletin_threads WHERE deleted_at IS NULL AND scope_type = :scopeType AND scope_id = :scopeId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE)";

    /**
     * スコープごとのスレッドをページング取得する（ピン留め優先→更新日時降順）。
     */
    Page<BulletinThreadEntity> findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
            ScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * カテゴリ指定でスレッドをページング取得する。
     */
    Page<BulletinThreadEntity> findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
            Long categoryId, Pageable pageable);

    /**
     * IDとスコープでスレッドを取得する。
     */
    Optional<BulletinThreadEntity> findByIdAndScopeTypeAndScopeId(Long id, ScopeType scopeType, Long scopeId);

    /**
     * 全文検索でスレッドを取得する。
     */
    @Query(value = SEARCH_QUERY, countQuery = SEARCH_COUNT_QUERY, nativeQuery = true)
    Page<BulletinThreadEntity> searchByKeyword(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * スコープ内のピン留めスレッド一覧を取得する。
     */
    List<BulletinThreadEntity> findByScopeTypeAndScopeIdAndIsPinnedTrueOrderByUpdatedAtDesc(
            ScopeType scopeType, Long scopeId);

    /**
     * カテゴリに属するスレッド数を取得する。
     */
    long countByCategoryId(Long categoryId);
}
