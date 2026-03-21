package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ブログ記事リポジトリ。
 */
public interface BlogPostRepository extends JpaRepository<BlogPostEntity, Long> {

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

    @Query(value = "SELECT * FROM blog_posts WHERE team_id = :teamId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL",
            nativeQuery = true)
    Page<BlogPostEntity> searchByTeam(@Param("teamId") Long teamId, @Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT * FROM blog_posts WHERE organization_id = :orgId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL",
            nativeQuery = true)
    Page<BlogPostEntity> searchByOrganization(@Param("orgId") Long orgId, @Param("keyword") String keyword, Pageable pageable);

    long countBySeriesId(Long seriesId);
}
