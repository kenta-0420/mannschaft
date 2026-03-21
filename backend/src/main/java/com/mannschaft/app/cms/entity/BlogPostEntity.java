package com.mannschaft.app.cms.entity;

import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ブログ記事・お知らせエンティティ。
 */
@Entity
@Table(name = "blog_posts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BlogPostEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    private Long userId;

    private Long socialProfileId;

    private Long authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 500)
    private String excerpt;

    @Column(length = 500)
    private String coverImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private PostType postType = PostType.BLOG;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Visibility visibility = Visibility.MEMBERS_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PostPriority priority = PostPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostStatus status = PostStatus.DRAFT;

    private LocalDateTime publishedAt;

    private LocalDateTime selfReviewDeadline;

    private LocalDateTime archiveAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean pinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowComments = false;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String targetType = "ALL";

    private Long targetId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean crossPostToTimeline = false;

    private Long timelinePostId;

    @Column(length = 500)
    private String rejectionReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short readingTimeMinutes = 0;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(length = 64)
    private String previewToken;

    private LocalDateTime previewTokenExpiresAt;

    private Long seriesId;

    private Short seriesOrder;

    private LocalDateTime deletedAt;

    /**
     * 記事のタイトル・本文・要約を更新する。
     */
    public void update(String title, String slug, String body, String excerpt,
                       String coverImageUrl, Visibility visibility, PostPriority priority,
                       Short readingTimeMinutes) {
        this.title = title;
        this.slug = slug;
        this.body = body;
        this.excerpt = excerpt;
        this.coverImageUrl = coverImageUrl;
        this.visibility = visibility;
        this.priority = priority;
        this.readingTimeMinutes = readingTimeMinutes;
    }

    /**
     * 公開ステータスを変更する。
     */
    public void changeStatus(PostStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 公開日時を設定する。
     */
    public void publish(LocalDateTime publishedAt) {
        this.status = PostStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.previewToken = null;
        this.previewTokenExpiresAt = null;
    }

    /**
     * 却下する。
     */
    public void reject(String rejectionReason) {
        this.status = PostStatus.REJECTED;
        this.rejectionReason = rejectionReason;
    }

    /**
     * セルフレビューに遷移する。
     */
    public void pendingSelfReview(LocalDateTime deadline) {
        this.status = PostStatus.PENDING_SELF_REVIEW;
        this.selfReviewDeadline = deadline;
    }

    /**
     * 閲覧数をインクリメントする。
     */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /**
     * プレビュートークンを設定する。
     */
    public void setPreviewToken(String token, LocalDateTime expiresAt) {
        this.previewToken = token;
        this.previewTokenExpiresAt = expiresAt;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
