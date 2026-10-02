package com.mannschaft.app.notification.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F04.3 グローバル通知設定エンティティ。1ユーザー1行（{@code UNIQUE KEY uq_ns_user}）。
 *
 * <p>現状は「優先度による自動配信」（{@code priorityAutoDelivery}）のみだが、将来の
 * グローバル設定（おやすみモード等）の置き場として拡張可能。</p>
 *
 * <p><b>主キー方針</b>: CLAUDE.md 原則 6 に従い {@link UuidV7Entity} を継承し
 * {@code id BINARY(16)} で UUIDv7 を採用。</p>
 *
 * <p><b>FK 方針</b>: クロスドメイン FK 禁止原則（CLAUDE.md 原則1）に従い、
 * {@code user_id} への FK は張らない。参照整合性はアプリケーション層で保証。</p>
 */
@Entity
@Table(name = "notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class NotificationSettingsEntity extends UuidV7Entity {

    /**
     * auth ドメインの users.id（FK は張らない・クロスドメイン FK 禁止原則準拠）。
     * UNIQUE KEY uq_ns_user により 1 ユーザー 1 行を保証。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 優先度による自動配信。
     * true = 単一モードの種別で priority に応じ自動的にプッシュも配信（NORMAL 以上）。
     * false = 自動でプッシュを飛ばさない（アプリ内のみ）。
     * Dual 展開済み種別（channelOverride=true）には影響しない。
     */
    @Column(name = "priority_auto_delivery", nullable = false)
    @Builder.Default
    private Boolean priorityAutoDelivery = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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

    /**
     * 優先度自動配信フラグを更新する（直接ミューテート）。
     *
     * @param priorityAutoDelivery 自動配信を有効にする場合 true
     */
    public void updatePriorityAutoDelivery(boolean priorityAutoDelivery) {
        this.priorityAutoDelivery = priorityAutoDelivery;
    }
}
