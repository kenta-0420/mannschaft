package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 歳時記×村史の年輪エンティティ（F17.2 Wave1 ④歳時記×村史の年輪・設計書 §6.2）。
 *
 * <p>歳時記（年中行事）に、その年ごとの「様子」（写真・一言メモ）を積む。
 * 1歳時記×1年に複数件を許す（{@code (calendar_event_id, year)} に UNIQUE を張らない・
 * 設計書 §6.3）。写真は既存 R2/MediaUrlResolver 方式（{@code photo_r2_key}）に従う。</p>
 *
 * <p>{@code calendar_event_id} は同一ドメイン（village）内の参照だが、原則1に従い
 * FK は張らずインデックスのみで整合を保証する。{@code year} は MySQL 予約語のため
 * DB 列名はバッククォート付きで宣言する。</p>
 */
@Entity
@Table(name = "village_calendar_event_logs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCalendarEventLogEntity extends UuidV7Entity {

    /** → village_calendar_events.id（同一ドメイン・FK非付与/index・原則1） */
    @Column(name = "calendar_event_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID calendarEventId;

    /** 記録対象の西暦年（例 2026）。MySQL 予約語のためバッククォート必須。 */
    @Column(name = "`year`", nullable = false)
    private Integer year;

    /** 写真（R2キー・MediaUrlResolver で署名URL化） */
    @Column(name = "photo_r2_key", length = 255)
    private String photoR2Key;

    /** 一言メモ */
    @Column(name = "note", length = 300)
    private String note;

    /** 記録者（FK非付与・原則1） */
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
