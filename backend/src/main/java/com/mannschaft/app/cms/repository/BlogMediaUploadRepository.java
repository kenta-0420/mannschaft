package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ブログメディアアップロードリポジトリ。
 * 画像・動画の両方を管理する（旧: BlogImageUploadRepository）。
 */
@Repository
public interface BlogMediaUploadRepository extends JpaRepository<BlogMediaUploadEntity, Long> {

    /** 記事に紐付くメディア一覧取得。 */
    List<BlogMediaUploadEntity> findByBlogPostId(Long blogPostId);

    /** R2 キーで検索。 */
    Optional<BlogMediaUploadEntity> findByS3Key(String s3Key);

    /**
     * 孤立メディアのクリーンアップ用。
     * blog_post_id IS NULL かつ created_at が cutoff より古いレコードを返す。
     */
    List<BlogMediaUploadEntity> findByBlogPostIdIsNullAndCreatedAtBefore(LocalDateTime cutoff);

    /** 記事内のメディア数カウント（種別別）。 */
    int countByBlogPostIdAndMediaType(Long blogPostId, String mediaType);
}
