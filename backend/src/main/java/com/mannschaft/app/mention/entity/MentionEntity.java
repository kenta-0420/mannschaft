package com.mannschaft.app.mention.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンションエンティティ。
 *
 * <p>{@code @mention} でユーザーに通知されたメンションを管理する。
 * ポリモーフィック設計で {@code targetType + targetId} により
 * タイムライン投稿・チャットメッセージ等の対象レコードを参照する。</p>
 *
 * <p>mentions テーブルは updatedAt を持たない独自スキーマのため、
 * BaseEntity を継承せず独自に定義する。</p>
 */
@Entity
@Table(name = "mentions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class MentionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mentioned_user_id", nullable = false)
    private Long mentionedUserId;

    @Column(name = "mentioned_by_id", nullable = false)
    private Long mentionedById;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "content_snippet", length = 200)
    private String contentSnippet;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * メンションを既読にする。
     */
    public void markAsRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }
}
