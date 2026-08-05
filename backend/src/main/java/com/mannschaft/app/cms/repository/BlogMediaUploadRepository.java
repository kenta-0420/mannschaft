package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 孤立メディア 1 件を条件付きで物理削除し、実際に削除できた行数を返す（クリーンアップバッチ用）。
     *
     * <p>削除条件に {@code blog_post_id IS NULL} を含めるため、<b>行を確保できた実行だけが 1 を受け取る</b>。
     * クリーンアップバッチはこの戻り値が 1 のときに限りストレージ使用量を減算する。
     * 使用量更新は read-modify-write であり、同じ行を 2 つの実行が処理すると同じ容量が 2 回引かれて
     * {@code used_bytes} が過少になるが、条件付き DELETE は行ロックで直列化されるため
     * 2 回目以降は必ず 0 行となり、この二重減算が構造的に起こらない。</p>
     *
     * @param id 対象メディアID
     * @return 実際に削除できた行数（0 なら他の実行が処理済み）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM BlogMediaUploadEntity m WHERE m.id = :id AND m.blogPostId IS NULL")
    int deleteOrphanById(@Param("id") Long id);

    /** 記事内のメディア数カウント（種別別）。 */
    int countByBlogPostIdAndMediaType(Long blogPostId, String mediaType);
}
