package com.mannschaft.app.common.storage.quota.entity;

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
 * F13 ストレージ使用量変動履歴（{@code storage_usage_logs}）。
 *
 * <p>INSERT のみ。UPDATE / DELETE 不可。1年間保持後にバッチで物理削除する。</p>
 */
@Entity
@Table(name = "storage_usage_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class StorageUsageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "delta_bytes", nullable = false)
    private Long deltaBytes;

    @Column(name = "after_bytes", nullable = false)
    private Long afterBytes;

    @Column(name = "feature_type", nullable = false, length = 30)
    private String featureType;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
