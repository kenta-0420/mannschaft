package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.entity.enums.VillageReportTargetType;
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
 * 村内通報エンティティ（F17.1 Phase 1）。
 *
 * <p>通報者ユーザーID は閲覧 API では返却しない（被報告者非開示・セキュリティ要件）。</p>
 */
@Entity
@Table(name = "village_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageReportEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 通報者ユーザーID（FK 張らない／原則1） */
    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private VillageReportTargetType targetType;

    /** 対象 ID（型は target_type に依存・文字列で保持） */
    @Column(name = "target_ref_id", nullable = false, length = 64)
    private String targetRefId;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageReportStatus status;

    /** 処理担当の村長/長老メンバーシップID（村ドメイン内・FK 張らない） */
    @Column(name = "handler_membership_id", columnDefinition = "BINARY(16)")
    private UUID handlerMembershipId;

    @Column(name = "handler_action", length = 64)
    private String handlerAction;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

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
