package com.mannschaft.app.cms.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * ブログメディアアップロード管理エンティティ。
 * 画像・動画の両方を扱う（旧: BlogImageUploadEntity）。
 */
@Entity
@Table(name = "blog_media_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BlogMediaUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long blogPostId;

    private Long uploaderId;

    /** メディア種別（DB: ENUM('IMAGE','VIDEO')、デフォルト 'IMAGE'）。 */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String mediaType = "IMAGE";

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    /** 動画のサムネイル R2 キー（NULL = 未生成 or IMAGE）。 */
    @Column(name = "thumbnail_r2_key", length = 500)
    private String thumbnailR2Key;

    /** ファイルサイズ（bytes）。動画対応のため BIGINT に拡張済み。 */
    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false, length = 50)
    private String contentType;

    /** 動画の再生時間（秒）。IMAGE は常に NULL。 */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** 後処理ステータス。IMAGE は常に READY。VIDEO は PENDING → PROCESSING → READY/FAILED。 */
    @Column(name = "processing_status", nullable = false, length = 20)
    @Builder.Default
    private String processingStatus = "READY";

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 記事との紐付けを設定する。
     */
    public void linkToPost(Long blogPostId) {
        this.blogPostId = blogPostId;
    }

    /**
     * 記事との紐付けを解除する。
     */
    public void unlinkFromPost() {
        this.blogPostId = null;
    }

    /**
     * 動画サムネイルのキーを設定する（Workers による非同期生成後）。
     */
    public void updateThumbnail(String thumbnailR2Key) {
        this.thumbnailR2Key = thumbnailR2Key;
    }

    /**
     * 処理ステータスを更新する（動画処理完了時等）。
     */
    public void updateProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }
}
