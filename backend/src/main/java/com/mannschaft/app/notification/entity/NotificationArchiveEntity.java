package com.mannschaft.app.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知アーカイブエンティティ。保持期間を超えた {@code notifications} 行の退避先
 * （{@code notifications_archive} 表）に対応する。
 *
 * <p>移送は元の {@code notifications.id} をそのまま引き継ぐため、主キーは
 * {@code @Id} のみで採番しない（{@code @GeneratedValue} を付けない）。
 * per-row 状態（is_read / read_at / snoozed_until / priority / scope_type /
 * organization_id）を保持し、移送後も履歴の意味を失わない。FK は持たない
 * （参照整合性はアプリ層で保証・モジュラーモノリス原則1）。</p>
 *
 * <p>DDL: {@code V173.20260730033807__create_notifications_archive_and_read_index.sql}。</p>
 */
@Entity
@Table(name = "notifications_archive")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationArchiveEntity {

    /** notifications.id をそのまま引き継ぐ（採番しない）。 */
    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(nullable = false, length = 10)
    private String priority;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    /** notifications.source_type と一致（nullable）。 */
    @Column(name = "source_type", length = 50)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "channels_sent", columnDefinition = "JSON")
    private String channelsSent;

    @Column(name = "snoozed_until")
    private LocalDateTime snoozedUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 移送日時。 */
    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;
}
