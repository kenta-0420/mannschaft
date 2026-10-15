package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ブログ記事共有リポジトリ。
 */
public interface BlogPostShareRepository extends JpaRepository<BlogPostShareEntity, Long> {

    List<BlogPostShareEntity> findByBlogPostId(Long blogPostId);

    Optional<BlogPostShareEntity> findByBlogPostIdAndTeamId(Long blogPostId, Long teamId);

    Optional<BlogPostShareEntity> findByBlogPostIdAndOrganizationId(Long blogPostId, Long organizationId);

    List<BlogPostShareEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    List<BlogPostShareEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
