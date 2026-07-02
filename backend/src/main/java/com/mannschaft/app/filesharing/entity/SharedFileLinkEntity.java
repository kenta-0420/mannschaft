package com.mannschaft.app.filesharing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * ファイル共有リンクエンティティ。外部共有用のトークンベースリンクを管理する。
 */
@Entity
@Table(name = "shared_file_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SharedFileLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fileId;

    @Column(nullable = false, length = 36)
    private String token;

    private LocalDateTime expiresAt;

    @Column(length = 255)
    private String passwordHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer accessCount = 0;

    /**
     * PR-D: 手動失効フラグ。{@code false} で発行者がリンクを即時無効化できる（アクセス時 410 Gone）。
     * {@code columnDefinition} を DDL（V136.001）と揃え、ddl-auto テストのデフォルト不一致を防ぐ。
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    @Builder.Default
    private boolean active = true;

    /**
     * PR-D: このリンクで DL URL 発行を許すか。マスター確定仕様で既定 {@code false}（＝閲覧のみ）。
     * 公開リンク DL は {@code downloadAllowed}（リンク）かつ NOT {@code downloadDisabled}（ファイル/フォルダ・C 由来）
     * の AND 評価であり、リンクで許可しても C が禁止していれば DL 不可（C 優先）。
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private boolean downloadAllowed = false;

    private LocalDateTime lastAccessedAt;

    private Long createdBy;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * リンクが有効期限切れかどうかを判定する。
     *
     * @return 有効期限が設定されており、現在時刻が有効期限を過ぎている場合 true
     */
    public boolean isExpired() {
        return this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * アクセスカウントをインクリメントする。
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    /**
     * リンクを手動失効させる（発行者による即時無効化）。以降のアクセスは 410 Gone（LINK_INACTIVE）。
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * リンクが失効しているか（手動失効 or 期限切れ）を判定する。
     *
     * @return {@code active=false} または期限切れなら true
     */
    public boolean isInactiveOrExpired() {
        return !this.active || isExpired();
    }
}
