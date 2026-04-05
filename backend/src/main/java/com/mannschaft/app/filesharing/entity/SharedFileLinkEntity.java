package com.mannschaft.app.filesharing.entity;

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
 * ファイル共有リンクエンティティ。外部共有用のトークンベースリンクを管理する。
 */
@Entity
@Table(name = "shared_file_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
}
