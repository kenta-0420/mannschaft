package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレター設定エンティティ（F17.1 Phase 3-β-E）。
 *
 * <p>村×頻度（WEEKLY / MONTHLY）で 1 レコード。{@code is_enabled} で
 * 村単位の全停止を制御し、{@code last_sent_at} / {@code next_scheduled_at} を
 * 配信バッチが更新する。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: village_id 以外の FK は張らない。</li>
 *   <li>原則6: 新規テーブルのため UUIDv7 を採用。</li>
 * </ul>
 */
@Entity
@Table(name = "village_newsletters")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private VillageNewsletterFrequency frequency;

    /** 村単位で全停止できるフラグ。デフォルト TRUE。 */
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled;

    /** 直近の配信実行時刻（バッチが更新）。 */
    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    /** 次回配信予定時刻（運用観測用、必須ではない）。 */
    @Column(name = "next_scheduled_at")
    private LocalDateTime nextScheduledAt;

    /** 論理削除。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.isEnabled == null) {
            this.isEnabled = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
