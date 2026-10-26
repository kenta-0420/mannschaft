package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村作成申請エンティティ（F17.1 Phase 1）。
 *
 * <p>独立テーブル（FK なし）。申請承認時に villages レコードを作成し、
 * その ID を {@code createdVillageId} に記録する。</p>
 */
@Entity
@Table(name = "village_creation_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCreationRequestEntity extends UuidV7Entity {

    /** 申請者ユーザーID（FK 張らない／原則1） */
    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "proposed_name", nullable = false, length = 100)
    private String proposedName;

    @Column(name = "proposed_slug", nullable = false, length = 64)
    private String proposedSlug;

    @Column(name = "proposed_category", length = 64)
    private String proposedCategory;

    @Column(name = "purpose", nullable = false, columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "proposed_guideline_md", columnDefinition = "MEDIUMTEXT")
    private String proposedGuidelineMd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageRequestStatus status;

    /** 審査担当（運営）ユーザーID（FK 張らない） */
    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 承認時に作成された村ID */
    @Column(name = "created_village_id", columnDefinition = "BINARY(16)")
    private UUID createdVillageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
