package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知設定エンティティ。
 *
 * <p>チーム・組織ごとのデフォルトリマインド設定や
 * 送信者アラート閾値を管理する。</p>
 */
@Entity
@Table(
        name = "confirmable_notification_settings",
        uniqueConstraints = @UniqueConstraint(name = "uq_cns_scope", columnNames = {"scope_type", "scope_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ConfirmableNotificationSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スコープ種別（TEAM / ORGANIZATION） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    /** スコープID（チームIDまたは組織ID） */
    @Column(nullable = false)
    private Long scopeId;

    /**
     * デフォルト1回目リマインド送信タイミング（分）。
     * NULL の場合はリマインドなし。
     */
    @Column
    private Integer defaultFirstReminderMinutes;

    /**
     * デフォルト2回目リマインド送信タイミング（分）。
     * NULL の場合はリマインドなし。
     */
    @Column
    private Integer defaultSecondReminderMinutes;

    /**
     * 送信者へのアラート閾値（確認率%）。
     * この割合を超えて未確認者が残った場合に送信者へ通知する。
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer senderAlertThresholdPercent = 80;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
