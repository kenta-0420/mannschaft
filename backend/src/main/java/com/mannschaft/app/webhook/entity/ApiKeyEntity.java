package com.mannschaft.app.webhook.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.webhook.ApiKeyScopePermission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * APIキーエンティティ。
 * 論理削除あり。
 */
@Entity
@Table(name = "api_keys")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ApiKeyEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 8)
    private String keyPrefix;

    // 共有 passwordEncoder（AuthConfig）は DelegatingPasswordEncoder（既定 argon2）であり、
    // encode() は "{argon2}$argon2id$v=19$m=...$..." 形式（約100文字）を返す。旧 bcrypt 前提の
    // 60 文字では収まらず INSERT 時に "Data too long" で失効するため 255 文字に拡張する
    // （V152 で本番カラムも VARCHAR(255) に拡張済み）。
    @Column(nullable = false, length = 255)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ApiKeyScopePermission scopePermission = ApiKeyScopePermission.READ_WRITE;

    @Column(nullable = false)
    @Builder.Default
    private int rateLimitPerHour = 1000;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * APIキーを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 有効期限切れかどうかを返す。
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /**
     * 最終使用日時を更新する。
     */
    public void recordUsage() {
        this.lastUsedAt = LocalDateTime.now();
    }
}
