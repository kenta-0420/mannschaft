package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
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
 * 村練習試合・募集エンティティ（F17.1 Phase 2）。
 *
 * <p>スポーツ系村向けに「対戦相手募集」「審判募集」「会場提供募集」を汎用テンプレで管理する。
 * チーム代表として投稿する場合は {@code postedByTeamId} を持つ（FK 張らない・原則1）。</p>
 */
@Entity
@Table(name = "village_match_recruits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMatchRecruitEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 募集投稿者ユーザーID（FK 張らない・原則1） */
    @Column(name = "posted_by_user_id", nullable = false)
    private Long postedByUserId;

    /** チーム代表として投稿の場合のチームID（FK 張らない・原則1） */
    @Column(name = "posted_by_team_id")
    private Long postedByTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private VillageMatchRecruitCategory category;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "match_date", nullable = false)
    private LocalDate matchDate;

    @Column(name = "match_time_start")
    private LocalTime matchTimeStart;

    @Column(name = "match_time_end")
    private LocalTime matchTimeEnd;

    /** 場所（自由文字列） */
    @Column(name = "venue", length = 200)
    private String venue;

    /** 募集人数 / チーム数 */
    @Column(name = "required_count")
    private Integer requiredCount;

    /** 連絡方法（自由文字列） */
    @Column(name = "contact_method", length = 200)
    private String contactMethod;

    /** 応募締切（UTC） */
    @Column(name = "application_deadline")
    private LocalDateTime applicationDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageMatchRecruitStatus status;

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
            this.status = VillageMatchRecruitStatus.OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
