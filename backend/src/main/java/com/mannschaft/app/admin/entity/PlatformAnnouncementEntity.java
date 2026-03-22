package com.mannschaft.app.admin.entity;

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
 * プラットフォームお知らせエンティティ。論理削除あり。
 */
@Entity
@Table(name = "platform_announcements")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PlatformAnnouncementEntity extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String priority = "NORMAL";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String targetScope = "ALL";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    private LocalDateTime publishedAt;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * お知らせを更新する。
     *
     * @param title       タイトル
     * @param body        本文
     * @param priority    優先度
     * @param targetScope 対象スコープ
     * @param isPinned    ピン留め
     * @param expiresAt   有効期限
     */
    public void update(String title, String body, String priority,
                       String targetScope, Boolean isPinned, LocalDateTime expiresAt) {
        this.title = title;
        this.body = body;
        this.priority = priority;
        this.targetScope = targetScope;
        this.isPinned = isPinned;
        this.expiresAt = expiresAt;
    }

    /**
     * お知らせを公開する。
     */
    public void publish() {
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
