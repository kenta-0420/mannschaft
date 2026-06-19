package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村練習試合募集への応募エンティティ（F17.1 Phase 2）。
 *
 * <p>UNIQUE(recruit_id, applicant_user_id, status) により PENDING 二重応募を DB 層で防ぐ。
 * 履歴は status 違いで複数並存可能（一度 REJECTED されても再応募して PENDING を作れる設計）。</p>
 */
@Entity
@Table(name = "village_match_recruit_applications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class VillageMatchRecruitApplicationEntity extends UuidV7Entity {

    /** FK → village_match_recruits.id（同一ドメイン CASCADE） */
    @Column(name = "recruit_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID recruitId;

    /** 応募者ユーザーID（FK 張らない・原則1） */
    @Column(name = "applicant_user_id", nullable = false)
    private Long applicantUserId;

    /** チーム応募の場合のチームID（FK 張らない・原則1） */
    @Column(name = "applicant_team_id")
    private Long applicantTeamId;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageMatchApplicationStatus status;

    /** 審査ユーザーID（FK 張らない・原則1） */
    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

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
            this.status = VillageMatchApplicationStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
