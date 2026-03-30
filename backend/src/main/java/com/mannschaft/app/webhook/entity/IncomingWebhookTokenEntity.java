package com.mannschaft.app.webhook.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 受信WebhookトークンEntity。
 * 論理削除あり。
 */
@Entity
@Table(name = "incoming_webhook_tokens")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class IncomingWebhookTokenEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 36, unique = true)
    private String token;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String defaultUsername;

    @Column(length = 500)
    private String defaultAvatarUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    private LocalDateTime lastUsedAt;

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
     * トークンを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 最終使用日時を更新する。
     */
    public void recordUsage() {
        this.lastUsedAt = LocalDateTime.now();
    }
}
