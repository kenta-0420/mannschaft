package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 寄合エンティティ（F17.1 Phase 3-β）。
 *
 * <p>村人同士のオフ会・集まりの日程調整のための本体エンティティ。
 * 候補日複数提示 → 投票（AVAILABLE/MAYBE/UNAVAILABLE）→ 幹事が確定日決定 のフロー。</p>
 *
 * <p>子テーブル {@code village_meetup_candidate_dates} と
 * {@code village_meetup_votes} は同一ドメイン CASCADE で連動削除される。</p>
 */
@Entity
@Table(name = "village_meetups")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 幹事ユーザーID（FK 張らない・原則1） */
    @Column(name = "organizer_user_id", nullable = false)
    private Long organizerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageMeetupStatus status;

    /** 確定日（CONFIRMED 時のみセット） */
    @Column(name = "confirmed_date")
    private LocalDate confirmedDate;

    /** 確定時刻（CONFIRMED 時のみセット・NULL は終日）。#2357 */
    @Column(name = "confirmed_time")
    private LocalTime confirmedTime;

    /** 集合場所（任意） */
    @Column(name = "location", length = 300)
    private String location;

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
            this.status = VillageMeetupStatus.PLANNING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
