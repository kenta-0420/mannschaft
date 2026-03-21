package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogImageUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ画像アップロードリポジトリ。
 */
public interface BlogImageUploadRepository extends JpaRepository<BlogImageUploadEntity, Long> {

    List<BlogImageUploadEntity> findByBlogPostId(Long blogPostId);

    List<BlogImageUploadEntity> findByBlogPostIdIsNullAndCreatedAtBefore(LocalDateTime threshold);

    long countByBlogPostId(Long blogPostId);
}
