package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * 組織の締め日設定の先頭行（組織1行。締め・変更の直列化ロック対象。CMP-260909-2152 確定設計 r12 §1.1）。
 *
 * <p>revision は締め日変更のたびに +1 する世代。必要時に INSERT IGNORE で作る（GET は作らない）。</p>
 */
@Entity
@Table(name = "shift_budget_closing_settings",
        uniqueConstraints = @UniqueConstraint(name = "uq_sbcs_organization", columnNames = "organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetClosingSettingEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void stampCreated() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** 変更を1回受理した（revision を +1）。 */
    public void advanceRevision() {
        this.revision = this.revision + 1;
    }
}
