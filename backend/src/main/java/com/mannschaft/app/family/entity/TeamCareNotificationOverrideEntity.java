package com.mannschaft.app.family.entity;

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
 * チーム単位のケア通知上書き設定エンティティ。
 * チームまたは組織スコープでの通知フラグをケアリンクごとに上書きする。F03.12。
 */
@Entity
@Table(name = "team_care_notification_overrides")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamCareNotificationOverrideEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スコープ種別。"TEAM" または "ORGANIZATION"。 */
    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long careLinkId;

    /** NULL = デフォルト使用、TRUE/FALSE = 上書き */
    private Boolean notifyOnRsvp;
    private Boolean notifyOnCheckin;
    private Boolean notifyOnCheckout;
    private Boolean notifyOnAbsentAlert;
    private Boolean notifyOnDismissal;

    /** true の場合、このスコープではすべての通知を無効にする。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean disabled = false;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 通知上書き設定を更新する。
     */
    public void updateSettings(Boolean notifyOnRsvp, Boolean notifyOnCheckin,
                                Boolean notifyOnCheckout, Boolean notifyOnAbsentAlert,
                                Boolean notifyOnDismissal, Boolean disabled) {
        this.notifyOnRsvp = notifyOnRsvp;
        this.notifyOnCheckin = notifyOnCheckin;
        this.notifyOnCheckout = notifyOnCheckout;
        this.notifyOnAbsentAlert = notifyOnAbsentAlert;
        this.notifyOnDismissal = notifyOnDismissal;
        if (disabled != null) this.disabled = disabled;
        this.updatedAt = LocalDateTime.now();
    }
}
