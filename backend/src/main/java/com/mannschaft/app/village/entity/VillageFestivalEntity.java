package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
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
 * 村お祭りエンティティ（F17.1 Phase 2）。
 *
 * <p>期間付き notice として動作し、{@code SCHEDULED → ACTIVE → ENDED} の自動遷移は
 * 別途バッチで実装予定（{@code idx_vf_active_period} を利用）。</p>
 *
 * <p>タイムゾーンは Phase 2 では UTC 固定。村ローカル TZ 対応は Phase 3 へ繰越。</p>
 */
@Entity
@Table(name = "village_festivals")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageFestivalEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 開始（UTC） */
    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** 終了（UTC） */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    /** バナー画像 R2 キー */
    @Column(name = "banner_r2_key", length = 255)
    private String bannerR2Key;

    /** テーマ色 #RRGGBB */
    @Column(name = "theme_color_hex", length = 7)
    private String themeColorHex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageFestivalStatus status;

    /** 作成者ユーザーID（FK 張らない・原則1） */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    /** 論理削除 */
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
        if (this.status == null) {
            this.status = VillageFestivalStatus.SCHEDULED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
