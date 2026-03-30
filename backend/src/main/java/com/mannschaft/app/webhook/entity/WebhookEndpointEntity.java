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
 * Webhookエンドポイントエンティティ。
 */
@Entity
@Table(name = "webhook_endpoints")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WebhookEndpointEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(nullable = false, length = 64)
    private String signingSecret;

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private int timeoutMs = 5000;

    @Column(nullable = false)
    @Builder.Default
    private int consecutiveFailureCount = 0;

    private LocalDateTime lastFailureAt;

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
     * エンドポイントを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * エンドポイントを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 連続失敗カウントをインクリメントし、最終失敗日時を更新する。
     */
    public void recordFailure() {
        this.consecutiveFailureCount++;
        this.lastFailureAt = LocalDateTime.now();
    }

    /**
     * 連続失敗カウントをリセットする（成功時）。
     */
    public void resetFailureCount() {
        this.consecutiveFailureCount = 0;
    }
}
