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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村歳時記カレンダーエンティティ（F17.1 Phase 2）。
 *
 * <p>「桃の節句」「七夕」「年越し」など、村ごとの年中行事を登録する。
 * 単発イベントもこのテーブルで扱う（{@code isAnnualRecurring=false}）。</p>
 *
 * <p>RFC 5545 RRULE は Phase 2 では導入せず、毎年繰返 or 単発の二択のみ。
 * {@code isAnnualRecurring=true} のときは {@code eventDate} の年部分は無視され、
 * 月日のみが意味を持つ。</p>
 */
@Entity
@Table(name = "village_calendar_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCalendarEventEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 基準日（is_annual_recurring=TRUE 時は年無視・月日のみ意味あり） */
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /** 複数日イベントの終了日（NULL = 単日） */
    @Column(name = "event_end_date")
    private LocalDate eventEndDate;

    /** 毎年繰返すか（TRUE: 年中行事 / FALSE: 単発） */
    @Column(name = "is_annual_recurring", nullable = false)
    private Boolean isAnnualRecurring;

    /** 表示絵文字（🌸 🎋 ⛄ など） */
    @Column(name = "icon_emoji", length = 20)
    private String iconEmoji;

    /** カレンダー表示色 #RRGGBB */
    @Column(name = "color_hex", length = 7)
    private String colorHex;

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
        if (this.isAnnualRecurring == null) {
            this.isAnnualRecurring = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
