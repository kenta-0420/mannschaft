package com.mannschaft.app.residencestatus.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.16 年次更新キャンペーンに対する各居住者の回答メタ。
 *
 * <p>{@code annual_review_id} は同ドメイン {@link AnnualReview} への FK CASCADE（CLAUDE.md DB設計原則 2 準拠）。
 * 他のクロスドメイン参照（dwelling_unit_id / resident_registry_id / respondent_user_id）は INDEX のみで FK なし。</p>
 *
 * <p>1 キャンペーン × 1 居住者 = 1 行（{@code uq_arr_review_resident}）。</p>
 */
@Entity
@Table(name = "annual_review_responses")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AnnualReviewResponse extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** annual_reviews.id（同ドメイン UUIDv7 FK CASCADE） */
    @Column(name = "annual_review_id", nullable = false)
    private UUID annualReviewId;

    /** F09.1 dwelling_units.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    /** F09.1 resident_registry.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /** 回答者（クロスドメイン弱参照・FK なし） */
    @Column(name = "respondent_user_id", nullable = false)
    private Long respondentUserId;

    /** 居住実態状態 enum: UNRESPONDED / OWNER_RESIDING / RENTED_OUT / LONG_ABSENCE / VACANT / OTHER */
    @Column(name = "residence_state", nullable = false, length = 30)
    private String residenceState;

    @Column(name = "contact_phone_verified", nullable = false)
    private Boolean contactPhoneVerified;

    @Column(name = "contact_email_verified", nullable = false)
    private Boolean contactEmailVerified;

    @Column(name = "emergency_contact_verified", nullable = false)
    private Boolean emergencyContactVerified;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.residenceState == null) {
            this.residenceState = "UNRESPONDED";
        }
        if (this.contactPhoneVerified == null) {
            this.contactPhoneVerified = false;
        }
        if (this.contactEmailVerified == null) {
            this.contactEmailVerified = false;
        }
        if (this.emergencyContactVerified == null) {
            this.emergencyContactVerified = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
