package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ブログ記事タグ中間テーブルリポジトリ。
 */
public interface BlogPostTagRepository extends JpaRepository<BlogPostTagEntity, BlogPostTagEntity.BlogPostTagId> {

    List<BlogPostTagEntity> findByBlogPostId(Long blogPostId);

    List<BlogPostTagEntity> findByBlogTagId(Long blogTagId);

    void deleteByBlogPostId(Long blogPostId);

    long countByBlogPostId(Long blogPostId);
}
