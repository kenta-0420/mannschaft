package com.mannschaft.app.common.storage.quota.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F13 スコープ別ストレージサブスクリプション（{@code storage_subscriptions}）。
 *
 * <p>各スコープ（組織・チーム・個人）が現在契約しているプランと使用量をリアルタイムで管理する。
 * {@code (scope_type, scope_id)} の組合せで一意。</p>
 */
@Entity
@Table(name = "storage_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class StorageSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ORGANIZATION / TEAM / PERSONAL */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes;

    @Column(name = "file_count", nullable = false)
    private Integer fileCount;

    @Column(name = "last_notified_threshold")
    private Short lastNotifiedThreshold;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.usedBytes == null) {
            this.usedBytes = 0L;
        }
        if (this.fileCount == null) {
            this.fileCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 使用量の変動を反映する（{@code delta} は正で増加・負で減少）。
     * 使用量と件数は 0 を下回らないようにクランプする。
     */
    public void applyDelta(long deltaBytes, int deltaCount) {
        long next = this.usedBytes + deltaBytes;
        this.usedBytes = next < 0 ? 0L : next;
        int nextCount = this.fileCount + deltaCount;
        this.fileCount = nextCount < 0 ? 0 : nextCount;
    }
}
