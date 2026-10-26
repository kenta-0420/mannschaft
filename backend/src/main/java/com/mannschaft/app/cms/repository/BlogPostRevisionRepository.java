package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ブログ記事リビジョンリポジトリ。
 */
public interface BlogPostRevisionRepository extends JpaRepository<BlogPostRevisionEntity, Long> {

    List<BlogPostRevisionEntity> findByBlogPostIdOrderByCreatedAtDesc(Long blogPostId);

    Optional<BlogPostRevisionEntity> findByBlogPostIdAndRevisionNumber(Long blogPostId, Integer revisionNumber);

    long countByBlogPostId(Long blogPostId);

    Optional<BlogPostRevisionEntity> findFirstByBlogPostIdOrderByRevisionNumberAsc(Long blogPostId);
}
