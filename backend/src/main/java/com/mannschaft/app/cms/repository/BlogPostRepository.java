package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.visibility.BlogPostVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ブログ記事リポジトリ。
 */
public interface BlogPostRepository extends JpaRepository<BlogPostEntity, Long> {

    String SEARCH_BY_TEAM = "SELECT * FROM blog_posts WHERE team_id = :teamId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL";
    String SEARCH_BY_ORG = "SELECT * FROM blog_posts WHERE organization_id = :orgId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL";

    Page<BlogPostEntity> findByTeamIdAndStatusOrderByPinnedDescPublishedAtDesc(
            Long teamId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByOrganizationIdAndStatusOrderByPinnedDescPublishedAtDesc(
            Long organizationId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByUserIdAndStatusOrderByPublishedAtDesc(
            Long userId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByTeamIdOrderByPinnedDescCreatedAtDesc(Long teamId, Pageable pageable);

    Page<BlogPostEntity> findByOrganizationIdOrderByPinnedDescCreatedAtDesc(Long organizationId, Pageable pageable);

    Page<BlogPostEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<BlogPostEntity> findByTeamIdAndSlug(Long teamId, String slug);

    Optional<BlogPostEntity> findByOrganizationIdAndSlug(Long organizationId, String slug);

    Optional<BlogPostEntity> findByUserIdAndSlug(Long userId, String slug);

    @Query(value = SEARCH_BY_TEAM, nativeQuery = true)
    Page<BlogPostEntity> searchByTeam(@Param("teamId") Long teamId, @Param("keyword") String keyword, Pageable pageable);

    @Query(value = SEARCH_BY_ORG, nativeQuery = true)
    Page<BlogPostEntity> searchByOrganization(@Param("orgId") Long orgId, @Param("keyword") String keyword, Pageable pageable);

    long countBySeriesId(Long seriesId);

    List<BlogPostEntity> findTop20ByTeamIdAndStatusAndVisibilityOrderByPublishedAtDesc(
            Long teamId, PostStatus status, Visibility visibility);

    List<BlogPostEntity> findTop20ByOrganizationIdAndStatusAndVisibilityOrderByPublishedAtDesc(
            Long organizationId, PostStatus status, Visibility visibility);

    /**
     * F00 共通可視性基盤 (BlogPostVisibilityResolver) 向けバルク射影取得。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} は {@link BlogPostEntity} に
     * 付与されているが、constructor expression を使う本クエリでは適用されないため
     * WHERE 句で明示的に {@code deleted_at IS NULL} を指定する。
     *
     * <p>SQL 1 本で {@link BlogPostVisibilityProjection} を生成し、N+1 を防ぐ。
     *
     * @param ids 射影対象 blog_post_id 集合（空でない）
     * @return 実存する BlogPost の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.cms.visibility.BlogPostVisibilityProjection(
                bp.id,
                CASE
                    WHEN bp.teamId IS NOT NULL THEN 'TEAM'
                    WHEN bp.organizationId IS NOT NULL THEN 'ORGANIZATION'
                    ELSE NULL
                END,
                COALESCE(bp.teamId, bp.organizationId),
                bp.authorId,
                bp.visibilityTemplateId,
                bp.visibility,
                bp.status)
            FROM BlogPostEntity bp
            WHERE bp.id IN :ids AND bp.deletedAt IS NULL
            """)
    List<BlogPostVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
