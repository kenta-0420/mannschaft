package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.BaseEntity;
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
 * 掲示板スレッドエンティティ。スレッドの本文・状態・統計情報を管理する。
 */
@Entity
@Table(name = "bulletin_threads")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BulletinThreadEntity extends BaseEntity {

    @Column(nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Priority priority = Priority.INFO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReadTrackingMode readTrackingMode = ReadTrackingMode.COUNT_ONLY;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isLocked = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer readCount = 0;

    private LocalDateTime lastRepliedAt;

    @Column(length = 30)
    private String sourceType;

    private Long sourceId;

    private LocalDateTime deletedAt;

    /**
     * スレッドのタイトルと本文を更新する。
     *
     * @param title    タイトル
     * @param body     本文
     * @param priority 優先度
     */
    public void update(String title, String body, Priority priority) {
        this.title = title;
        this.body = body;
        this.priority = priority;
    }

    /**
     * ピン留め状態を切り替える。
     */
    public void togglePin() {
        this.isPinned = !this.isPinned;
    }

    /**
     * ロック状態を切り替える。
     */
    public void toggleLock() {
        this.isLocked = !this.isLocked;
    }

    /**
     * アーカイブする。
     */
    public void archive() {
        this.isArchived = true;
    }

    /**
     * 返信カウントをインクリメントし、最終返信日時を更新する。
     */
    public void incrementReplyCount() {
        this.replyCount++;
        this.lastRepliedAt = LocalDateTime.now();
    }

    /**
     * 返信カウントをデクリメントする。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * 既読カウントをインクリメントする。
     */
    public void incrementReadCount() {
        this.readCount++;
    }

    /**
     * 書き込み可能かどうかを判定する。
     *
     * @return ロック・アーカイブされていない場合 true
     */
    public boolean isWritable() {
        return !this.isLocked && !this.isArchived;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
