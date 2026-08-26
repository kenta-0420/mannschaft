package com.mannschaft.app.safetycheck.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import com.mannschaft.app.safetycheck.entity.SafetyCheckSourceType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 安否確認エンティティ。緊急安否確認の発信情報を管理する。
 */
@Entity
@Table(name = "safety_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SafetyCheckEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SafetyCheckScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(length = 200)
    private String title;

    @Column(length = 1000)
    private String message;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDrill = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SafetyCheckStatus status = SafetyCheckStatus.ACTIVE;

    /** 安否確認の発生源種別（NULL の場合は MANUAL 相当の既存データ）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    private SafetyCheckSourceType sourceType;

    private Integer reminderIntervalMinutes;

    private LocalDateTime lastReminderAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalTargetCount = 0;

    @Column(name = "admin_24h_notified", nullable = false)
    @Builder.Default
    private Boolean admin24hNotified = false;

    private Long bulletinThreadId;

    private Long createdBy;

    private LocalDateTime closedAt;

    private Long closedBy;

    /**
     * 安否確認をクローズする。
     *
     * @param userId クローズ操作者のユーザーID
     */
    public void close(Long userId) {
        this.status = SafetyCheckStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
        this.closedBy = userId;
    }

    /**
     * リマインド送信日時を更新する。
     */
    public void updateLastReminderAt() {
        this.lastReminderAt = LocalDateTime.now();
    }

    /**
     * 24時間通知済みフラグをセットする。
     */
    public void markAdmin24hNotified() {
        this.admin24hNotified = true;
    }

    /**
     * 対象者総数を設定する。
     *
     * @param count 対象者総数
     */
    public void updateTotalTargetCount(int count) {
        this.totalTargetCount = count;
    }
}
