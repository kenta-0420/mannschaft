package com.mannschaft.app.succession.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 5 段階エスカレーションエンティティ（F09.15 S1）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.7
 *
 * <p>滞納＋連絡不通の区分所有者に対して
 * STAGE_1_REMINDER → STAGE_2_EMERGENCY_CONTACT → STAGE_3_WATCHER_VISIT
 * → STAGE_4_DEATH_SUSPECTED → STAGE_5_LEGAL_PREP の 5 段階を
 * D+30 / D+60 / D+90 / D+120 / D+150 で自動進行させる。
 *
 * <p>1 居住者 1 エスカ（{@code resident_registry_id} + {@code deleted_at} の複合 UNIQUE）。
 */
@Entity
@Table(name = "delinquency_escalations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class DelinquencyEscalationEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /**
     * STAGE_1_REMINDER / STAGE_2_EMERGENCY_CONTACT / STAGE_3_WATCHER_VISIT /
     * STAGE_4_DEATH_SUSPECTED / STAGE_5_LEGAL_PREP
     */
    @Column(name = "current_stage", nullable = false, length = 30)
    @Builder.Default
    private String currentStage = "STAGE_1_REMINDER";

    @Column(name = "delinquency_started_at", nullable = false)
    private LocalDate delinquencyStartedAt;

    @Column(name = "last_contact_attempt_at")
    private LocalDateTime lastContactAttemptAt;

    @Column(name = "stage_1_completed_at")
    private LocalDateTime stage1CompletedAt;

    @Column(name = "stage_2_completed_at")
    private LocalDateTime stage2CompletedAt;

    @Column(name = "stage_3_completed_at")
    private LocalDateTime stage3CompletedAt;

    @Column(name = "stage_4_completed_at")
    private LocalDateTime stage4CompletedAt;

    @Column(name = "stage_5_completed_at")
    private LocalDateTime stage5CompletedAt;

    /** エスカレーション凍結（弁護士介入等）。 */
    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    @Column(name = "frozen_reason", columnDefinition = "TEXT")
    private String frozenReason;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等。 */
    @Column(name = "resolved_reason", length = 50)
    private String resolvedReason;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
        if (this.currentStage == null) {
            this.currentStage = "STAGE_1_REMINDER";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
