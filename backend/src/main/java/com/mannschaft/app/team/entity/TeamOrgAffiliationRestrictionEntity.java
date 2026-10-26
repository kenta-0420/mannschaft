package com.mannschaft.app.team.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * チーム加盟の申請・招待の再送制限（F01.2.1 §5.4）。
 *
 * <p>UNIQUE (organization_id, team_id, direction)。物理削除で管理する（deleted_at なし）。</p>
 */
@Entity
@Table(name = "team_org_affiliation_restrictions",
        uniqueConstraints = @UniqueConstraint(name = "uq_toar_org_team_dir",
                columnNames = {"organization_id", "team_id", "direction"}),
        indexes = {
                @Index(name = "idx_toar_team_dir", columnList = "team_id, direction"),
                @Index(name = "idx_toar_kind_until", columnList = "kind, restricted_until")
        })
@Check(name = "chk_toar_direction", constraints = "direction IN ('TEAM_APPLY','ORG_INVITE')")
@Check(name = "chk_toar_kind", constraints = "kind IN ('COOLDOWN','BLOCK')")
@Check(name = "chk_toar_reason", constraints = "reason IN ('REJECTED','DECLINED','WITHDRAWN','CANCELLED')")
@Check(name = "chk_toar_until", constraints = "(kind = 'BLOCK' AND restricted_until IS NULL) OR (kind = 'COOLDOWN' AND restricted_until IS NOT NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class TeamOrgAffiliationRestrictionEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL")
    private TeamOrgAffiliationDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL")
    private TeamOrgAffiliationRestrictionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL")
    private TeamOrgAffiliationRestrictionReason reason;

    @Column(name = "restricted_until")
    private Instant restrictedUntil;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * 合成規則（§5.4）に従って、新しい制限の内容で置き換える。
     *
     * <p>置き換えるかどうかの判定は
     * {@link com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionComposer#incomingWins} が行う。
     * 本メソッドは「置き換える」と決まった後の値の差し替えだけを担う
     * （{@code chk_toar_until}: BLOCK は {@code restricted_until} が NULL、COOLDOWN は非 NULL）。</p>
     */
    public void replaceWith(TeamOrgAffiliationRestrictionKind newKind, Instant newUntil,
                            TeamOrgAffiliationRestrictionReason newReason, Long newCreatedBy) {
        this.kind = newKind;
        this.restrictedUntil = newKind == TeamOrgAffiliationRestrictionKind.BLOCK ? null : newUntil;
        this.reason = newReason;
        this.createdBy = newCreatedBy;
    }
}
