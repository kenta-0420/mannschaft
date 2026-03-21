package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * タイムライン投稿リポジトリ。
 */
public interface TimelinePostRepository extends JpaRepository<TimelinePostEntity, Long> {

    /**
     * スコープ別フィード（新着順）を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId "
            + "AND p.parentId IS NULL AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findFeedByScopeType(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /**
     * ユーザーの投稿一覧を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.userId = :userId "
            + "AND p.parentId IS NULL AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId, Pageable pageable);

    /**
     * 投稿のリプライ一覧を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.parentId = :parentId "
            + "AND p.status = 'PUBLISHED' ORDER BY p.createdAt ASC")
    List<TimelinePostEntity> findRepliesByParentId(
            @Param("parentId") Long parentId, Pageable pageable);

    /**
     * ピン留め投稿一覧を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId "
            + "AND p.isPinned = true AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findPinnedPosts(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * 全文検索で投稿を取得する。
     */
    @Query(value = "SELECT * FROM timeline_posts WHERE MATCH(content) AGAINST(:keyword IN BOOLEAN MODE) "
            + "AND deleted_at IS NULL AND status = 'PUBLISHED' ORDER BY created_at DESC LIMIT :limit",
            nativeQuery = true)
    List<TimelinePostEntity> searchByKeyword(
            @Param("keyword") String keyword, @Param("limit") int limit);
}
