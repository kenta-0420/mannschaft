package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.17 ユーザー通報。通報 3 件で自動 SUSPEND 判定。保持期間 3 年。
 * 退会時は reporter_user_id を NULL 化。
 */
@Entity
@Table(name = "ad_user_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdUserReport extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** 通報者 (退会時 NULL 化・FK なし) */
    @Column(name = "reporter_user_id")
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private AdChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 20)
    private AdReportReasonCode reasonCode;

    @Column(name = "comment", length = 500)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.status == null) {
            this.status = AdReportStatus.NEW;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
