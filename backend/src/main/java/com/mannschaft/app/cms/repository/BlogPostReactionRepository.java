package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogPostReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * ブログ記事リアクション（みたよ！）リポジトリ。
 */
public interface BlogPostReactionRepository extends JpaRepository<BlogPostReactionEntity, Long> {

    /**
     * ユーザーが記事にリアクション済みかを判定する。
     */
    boolean existsByBlogPostIdAndUserId(Long blogPostId, Long userId);

    /**
     * 記事のリアクション数を取得する。
     */
    long countByBlogPostId(Long blogPostId);

    /**
     * ユーザーの記事リアクションを削除する。
     */
    @Modifying
    @Transactional
    void deleteByBlogPostIdAndUserId(Long blogPostId, Long userId);
}
