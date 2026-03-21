package com.mannschaft.app.digest.entity;

import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.DigestStyle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * タイムラインダイジェスト履歴エンティティ。
 * AI の生成結果とブログ記事への紐付けを管理する。
 * 論理削除なし（ステータス管理で制御）。
 */
@Entity
@Table(name = "timeline_digests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimelineDigestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long configId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DigestScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private LocalDateTime periodStart;

    @Column(nullable = false)
    private LocalDateTime periodEnd;

    private Integer postCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DigestStyle digestStyle;

    @Column(length = 200)
    private String generatedTitle;

    @Column(columnDefinition = "TEXT")
    private String generatedBody;

    @Column(length = 500)
    private String generatedExcerpt;

    @Column(length = 50)
    private String aiModel;

    private Integer aiInputTokens;

    private Integer aiOutputTokens;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DigestStatus status = DigestStatus.GENERATING;

    private Long blogPostId;

    private LocalDateTime generatingTimeoutAt;

    @Column(columnDefinition = "JSON")
    private String sourcePostIds;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long triggeredBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ========================================
    // Business methods
    // ========================================

    /**
     * ダイジェスト生成完了に遷移する。
     */
    public void markGenerated(String title, String body, String excerpt,
                               String model, Integer inputTokens, Integer outputTokens,
                               int postCount) {
        this.status = DigestStatus.GENERATED;
        this.generatedTitle = title;
        this.generatedBody = body;
        this.generatedExcerpt = excerpt;
        this.aiModel = model;
        this.aiInputTokens = inputTokens;
        this.aiOutputTokens = outputTokens;
        this.postCount = postCount;
    }

    /**
     * ダイジェスト生成失敗に遷移する。
     */
    public void markFailed(String errorMessage) {
        this.status = DigestStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /**
     * ダイジェストを公開済みに遷移する。
     */
    public void markPublished() {
        this.status = DigestStatus.PUBLISHED;
    }

    /**
     * ダイジェストを破棄に遷移する。
     */
    public void discard() {
        this.status = DigestStatus.DISCARDED;
    }

    /**
     * ダイジェストが編集可能か判定する（GENERATED のみ）。
     */
    public boolean isEditable() {
        return this.status == DigestStatus.GENERATED;
    }
}
