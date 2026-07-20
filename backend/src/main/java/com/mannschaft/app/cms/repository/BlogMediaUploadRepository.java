package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
     * R2 キーの一括検索（本文メディアの台帳照合用）。
     *
     * <p>{@code BlogBodyMediaResolver} が「本文に書かれた r2Key が実在するアップロードか」を
     * 検証する際に使う。本文 1 件につき 1 クエリで済ませるため、キーごとの
     * {@link #findByS3Key} ループ呼びを避けること。</p>
     */
    List<BlogMediaUploadEntity> findByS3KeyIn(Collection<String> s3Keys);

    /**
     * 孤立メディアのクリーンアップ用。
     * blog_post_id IS NULL かつ created_at が cutoff より古いレコードを返す。
     */
    List<BlogMediaUploadEntity> findByBlogPostIdIsNullAndCreatedAtBefore(LocalDateTime cutoff);

    /** 記事内のメディア数カウント（種別別）。 */
    int countByBlogPostIdAndMediaType(Long blogPostId, String mediaType);
}
