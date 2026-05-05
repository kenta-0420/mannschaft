package com.mannschaft.app.membership.entity;

import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * メンバーシップエンティティ。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。組織・チームへの入会・退会・再加入の履歴を
 * 期間（joined_at / left_at）付きで管理する。</p>
 *
 * <p>多態 1 表（{@code scope_type} = ORGANIZATION | TEAM）。{@code scope_id} への FK は
 * MySQL 8.0 が条件付き FK をサポートしないため張らない。整合性はアプリ層 + 監査バッチで担保する。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.1</p>
 */
@Entity
@Table(name = "memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属ユーザー ID。GDPR マスキング時に NULL 化されうる。
     */
    @Column(name = "user_id")
    @Setter
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 12)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /**
     * メンバー区分。MEMBER または SUPPORTER。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role_kind", nullable = false, length = 10)
    @Builder.Default
    private RoleKind roleKind = RoleKind.MEMBER;

    /** 入会日時。 */
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** 退会日時。NULL = アクティブ。 */
    @Column(name = "left_at")
    @Setter
    private LocalDateTime leftAt;

    /** 退会理由。left_at と同時に必須（CHECK 制約で保証）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_reason", length = 10)
    @Setter
    private LeaveReason leaveReason;

    /** 招待者の user_id。SUPPORTER 自己登録時は NULL。 */
    @Column(name = "invited_by")
    @Setter
    private Long invitedBy;

    /** GDPR 削除によるマスキング日時。NOT NULL のとき user_id IS NULL でなければならない。 */
    @Column(name = "gdpr_masked_at")
    @Setter
    private LocalDateTime gdprMaskedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.joinedAt == null) {
            this.joinedAt = now;
        }
        this.createdAt = now;
        this.updatedAt = now;
        if (this.roleKind == null) {
            this.roleKind = RoleKind.MEMBER;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * left_at がセットされていない（アクティブな）メンバーシップかを判定する。
     */
    public boolean isActive() {
        return this.leftAt == null;
    }
}
