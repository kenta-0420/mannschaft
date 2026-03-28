package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 広告レポートスケジュールエンティティ。
 */
@Entity
@Table(name = "ad_report_schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdReportScheduleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long advertiserAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportFrequency frequency;

    @Column(nullable = false, columnDefinition = "json")
    private String recipients;

    @Column(columnDefinition = "json")
    private String includeCampaigns;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime lastSentAt;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * スケジュールを有効化する。
     */
    public void enable() {
        this.enabled = true;
    }

    /**
     * スケジュールを無効化する。
     */
    public void disable() {
        this.enabled = false;
    }

    /**
     * 最終送信日時を現在時刻に更新する。
     */
    public void updateLastSentAt() {
        this.lastSentAt = LocalDateTime.now();
    }
}
