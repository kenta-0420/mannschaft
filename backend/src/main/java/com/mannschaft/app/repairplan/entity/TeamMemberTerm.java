package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 理事任期独立テーブル（F08.8 Phase 1）。
 *
 * <p>{@code team_members} 肥大化を回避し、申し送りパック・退会済元理事フィルタの基盤となる。
 * {@code user_id} は users.id への ID 参照（FK なし）、
 * {@code handover_pack_id} は board_handover_packs への ID 参照（循環依存回避のため FK なし）。
 * 不変ではないが論理削除は持たない（任期データは履歴として永続）。</p>
 */
@Entity
@Table(name = "team_member_terms")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class TeamMemberTerm extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** users.id（クロスドメイン参照・FK なし） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_label", nullable = false, length = 60)
    private String roleLabel;

    @Column(name = "term_start", nullable = false)
    private LocalDate termStart;

    @Column(name = "term_end", nullable = false)
    private LocalDate termEnd;

    @Column(name = "handover_pack_id")
    private UUID handoverPackId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

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
        if (this.isActive == null) {
            this.isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
