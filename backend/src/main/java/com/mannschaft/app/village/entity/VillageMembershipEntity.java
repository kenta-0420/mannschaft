package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
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
 * 村メンバーシップエンティティ（F17.1 Phase 1）。
 *
 * <p>個人・チーム・組織が村に参加する関係を表す。{@code subject_id} は
 * USER の場合 users.id、TEAM なら teams.id、ORGANIZATION なら organizations.id を
 * 保持するが、FK は張らない（原則1）。退村は {@code leftAt} で論理削除。</p>
 */
@Entity
@Table(name = "village_memberships")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMembershipEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private VillageSubjectType subjectType;

    /** 参加主体ID（FK 張らない／原則1） */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private VillageRole role;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** 退村日（論理削除） */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "banned_at")
    private LocalDateTime bannedAt;

    @Column(name = "banned_reason", length = 500)
    private String bannedReason;

    @Column(name = "invited_by_membership_id", columnDefinition = "BINARY(16)")
    private UUID invitedByMembershipId;

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
        if (this.joinedAt == null) {
            this.joinedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
