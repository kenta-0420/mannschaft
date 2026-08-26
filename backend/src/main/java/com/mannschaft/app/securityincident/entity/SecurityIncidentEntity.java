package com.mannschaft.app.securityincident.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.securityincident.SecurityIncidentSeverity;
import com.mannschaft.app.securityincident.SecurityIncidentStatus;
import com.mannschaft.app.securityincident.SecurityIncidentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * セキュリティインシデント管理エンティティ。
 *
 * <p>GDPR Article 33 の 72 時間以内 DPA 通知義務を管理するための
 * ドメインオブジェクト。新規テーブルのため UUIDv7 主キーを使用する（アーキテクチャ原則 6）。</p>
 */
@Entity
@Table(name = "security_incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SecurityIncidentEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SecurityIncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SecurityIncidentSeverity severity;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    private Integer recordsAffected;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private SecurityIncidentStatus status = SecurityIncidentStatus.OPEN;

    private LocalDateTime notifiedDpaAt;

    private LocalDateTime resolvedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.detectedAt == null) {
            this.detectedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ステータスを更新する。CLOSED かつ resolvedAt が指定された場合は解決日時も記録する。
     *
     * @param newStatus  新しいステータス
     * @param resolvedAt 解決日時（CLOSED 時のみ有効、null 可）
     */
    public void updateStatus(SecurityIncidentStatus newStatus, LocalDateTime resolvedAt) {
        this.status = newStatus;
        if (newStatus == SecurityIncidentStatus.CLOSED && resolvedAt != null) {
            this.resolvedAt = resolvedAt;
        }
    }

    /**
     * DPA（監督機関）への通知を記録する。
     *
     * @param notifiedAt 通知日時
     */
    public void markDpaNotified(LocalDateTime notifiedAt) {
        this.notifiedDpaAt = notifiedAt;
    }
}
