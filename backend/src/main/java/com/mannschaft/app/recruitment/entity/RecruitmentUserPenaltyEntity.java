package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * F03.11 Phase 5b: ユーザーペナルティ状態エンティティ。
 * recruitment_user_penalties テーブルに対応する。
 * アクティブペナルティの重複はサービス層の PESSIMISTIC_WRITE で防止する。
 */
@Entity
@Table(name = "recruitment_user_penalties")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentUserPenaltyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 現状は NO_SHOW のみ。将来の拡張用に ENUM 化。 */
    @Column(name = "penalty_type", nullable = false, length = 20)
    private String penaltyType = "NO_SHOW";

    @Column(name = "triggered_by_setting_id", nullable = false)
    private Long triggeredBySettingId;

    @Column(name = "triggered_no_show_count", nullable = false)
    private int triggeredNoShowCount;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "lifted_at")
    private LocalDateTime liftedAt;

    @Column(name = "lifted_by")
    private Long liftedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "lift_reason", length = 20)
    private PenaltyLiftReason liftReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public RecruitmentUserPenaltyEntity(
            Long userId, RecruitmentScopeType scopeType, Long scopeId,
            Long triggeredBySettingId, int triggeredNoShowCount,
            LocalDateTime startedAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.triggeredBySettingId = triggeredBySettingId;
        this.triggeredNoShowCount = triggeredNoShowCount;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    /** ペナルティが現在アクティブか（解除済み or 期限切れでないか）。 */
    public boolean isActive() {
        return liftedAt == null && LocalDateTime.now().isBefore(expiresAt);
    }

    /** ペナルティを解除する。 */
    public void lift(Long liftedByUserId, PenaltyLiftReason reason) {
        this.liftedAt = LocalDateTime.now();
        this.liftedBy = liftedByUserId;
        this.liftReason = reason;
    }
}
