package com.mannschaft.app.timeline.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * タイムライン投稿エンティティ。スコープ別（PUBLIC/ORGANIZATION/TEAM/PERSONAL）の投稿を管理する。
 */
@Entity
@Table(name = "timeline_posts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimelinePostEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostScopeType scopeType;

    @Column(nullable = false)
    @Builder.Default
    private Long scopeId = 0L;

    @Column(nullable = false)
    private Long userId;

    private Long socialProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostedAsType postedAsType = PostedAsType.USER;

    private Long postedAsId;

    private Long parentId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Long repostOfId;

    @Column(nullable = false)
    @Builder.Default
    private Integer repostCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostStatus status = PostStatus.PUBLISHED;

    private LocalDateTime scheduledAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer reactionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short attachmentCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short editCount = 0;

    private LocalDateTime deletedAt;

    /**
     * 投稿を論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.status = PostStatus.DELETED;
    }

    /**
     * 投稿を非表示にする（モデレーション対応）。
     */
    public void hide() {
        this.status = PostStatus.HIDDEN;
    }

    /**
     * 投稿内容を更新し、編集回数をインクリメントする。
     *
     * @param newContent 新しい投稿内容
     */
    public void updateContent(String newContent) {
        this.content = newContent;
        this.editCount = (short) (this.editCount + 1);
    }

    /**
     * リアクション数をインクリメントする。
     */
    public void incrementReactionCount() {
        this.reactionCount++;
    }

    /**
     * リアクション数をデクリメントする。
     */
    public void decrementReactionCount() {
        if (this.reactionCount > 0) {
            this.reactionCount--;
        }
    }

    /**
     * リプライ数をインクリメントする。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * リポスト数をインクリメントする。
     */
    public void incrementRepostCount() {
        this.repostCount++;
    }

    /**
     * ピン留め状態を切り替える。
     *
     * @param pinned ピン留めするかどうか
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
    }

    /**
     * 返信投稿かどうかを判定する。
     *
     * @return parentId が設定されている場合 true
     */
    public boolean isReply() {
        return this.parentId != null;
    }

    /**
     * リポスト投稿かどうかを判定する。
     *
     * @return repostOfId が設定されている場合 true
     */
    public boolean isRepost() {
        return this.repostOfId != null;
    }
}
