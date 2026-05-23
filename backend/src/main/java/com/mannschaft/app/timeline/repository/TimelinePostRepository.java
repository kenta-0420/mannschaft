package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * タイムライン投稿リポジトリ。
 */
public interface TimelinePostRepository extends JpaRepository<TimelinePostEntity, Long> {

    String SEARCH_QUERY = "SELECT * FROM timeline_posts WHERE MATCH(content) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL AND status = 'PUBLISHED' ORDER BY created_at DESC LIMIT :limit";

    /**
     * スコープ別フィード（新着順）を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId "
            + "AND p.parentId IS NULL AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findFeedByScopeType(
            @Param("scopeType") PostScopeType scopeType,
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
            @Param("scopeType") PostScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * 全文検索で投稿を取得する。
     */
    @Query(value = SEARCH_QUERY, nativeQuery = true)
    List<TimelinePostEntity> searchByKeyword(
            @Param("keyword") String keyword, @Param("limit") int limit);

    // ====================================================================
    // F19.1 Phase 7 — 公開タイムライン投稿（未ログインアクセス用）
    // TODO: クロスドメイン参照(publicview→timeline)。将来はイベント駆動で分離予定
    // ====================================================================

    /**
     * チームスコープの公開タイムライン投稿一覧を取得する（F19.1 Phase 7）。
     *
     * <p>対象: scopeType=TEAM、scopeId=teamId、status=PUBLISHED、public_visible=true、
     * 根投稿のみ（parentId IS NULL）。</p>
     *
     * @param teamId   チーム ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeType = com.mannschaft.app.timeline.PostScopeType.TEAM
              AND p.scopeId = :teamId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.publicVisible = true
            ORDER BY p.createdAt DESC
            """)
    Page<TimelinePostEntity> findPublicByTeamId(@Param("teamId") Long teamId, Pageable pageable);

    /**
     * 組織スコープの公開タイムライン投稿一覧を取得する（F19.1 Phase 7）。
     *
     * <p>対象: scopeType=ORGANIZATION、scopeId=orgId、status=PUBLISHED、public_visible=true、
     * 根投稿のみ（parentId IS NULL）。</p>
     *
     * @param orgId    組織 ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeType = com.mannschaft.app.timeline.PostScopeType.ORGANIZATION
              AND p.scopeId = :orgId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.publicVisible = true
            ORDER BY p.createdAt DESC
            """)
    Page<TimelinePostEntity> findPublicByOrganizationId(@Param("orgId") Long orgId, Pageable pageable);

    // ====================================================================
    // F17.1 Phase 1 — 村スコープ検索 / フィード（B10 担当範囲：読み取り専用）
    // ====================================================================

    /**
     * 村スコープのタイムライン投稿を部分一致で検索する（F17.1 §4.12）。
     *
     * <p>{@code scope_village_id} 一致 + parentId IS NULL（根投稿のみ）+ PUBLISHED ステータス。
     * 削除済みは {@code @SQLRestriction} により自動除外される。</p>
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> searchByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q,
            Pageable pageable);

    /** 村スコープのタイムライン投稿検索結果件数（ページャ用）。 */
    @Query("""
            SELECT COUNT(p) FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    long countByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q);

    /**
     * 村スコープの最新タイムライン投稿 N 件を返す（F17.1 §4.13 ダッシュボード集約用）。
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> findLatestByVillageId(
            @Param("villageId") UUID villageId, Pageable pageable);

    // ====================================================================
    // F17.1 Phase 3-β — 村史月次集計（村ドメインから read-only 呼出）
    // TODO: 将来は VillagePostCreatedEvent によるカウンタ非同期更新へ分離予定。
    // ====================================================================

    /**
     * 村スコープのタイムライン投稿件数を期間で集計する。
     */
    @Query("""
            SELECT COUNT(p) FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.createdAt >= :fromInclusive
              AND p.createdAt <  :toExclusive
            """)
    long countByVillageIdAndCreatedAtBetween(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);
}
