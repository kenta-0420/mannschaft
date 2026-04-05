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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ApiKeyEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 8)
    private String keyPrefix;

    @Column(nullable = false, length = 60)
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
