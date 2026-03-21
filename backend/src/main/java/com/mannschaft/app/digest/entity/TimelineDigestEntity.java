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
    private DigestStatus status;

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
}
