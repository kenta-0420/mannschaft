package com.mannschaft.app.chat.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * チャットメッセージエンティティ。チャンネル内のメッセージ・スレッド返信を管理する。
 */
@Entity
@Table(name = "chat_messages")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChatMessageEntity extends BaseEntity {

    @Column(nullable = false)
    private Long channelId;

    private Long senderId;

    private Long parentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private Long forwardedFromId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEdited = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    private LocalDateTime scheduledAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer reactionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    private LocalDateTime deletedAt;

    /**
     * メッセージ本文を編集する。
     *
     * @param newBody 新しいメッセージ本文
     */
    public void editBody(String newBody) {
        this.body = newBody;
        this.isEdited = true;
    }

    /**
     * メッセージをピン留めする。
     */
    public void pin() {
        this.isPinned = true;
    }

    /**
     * メッセージのピン留めを解除する。
     */
    public void unpin() {
        this.isPinned = false;
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
     * 返信数をインクリメントする。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * スレッド返信かどうかを判定する。
     *
     * @return parentId が設定されている場合 true
     */
    public boolean isReply() {
        return this.parentId != null;
    }

    /**
     * 転送メッセージかどうかを判定する。
     *
     * @return forwardedFromId が設定されている場合 true
     */
    public boolean isForwarded() {
        return this.forwardedFromId != null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
