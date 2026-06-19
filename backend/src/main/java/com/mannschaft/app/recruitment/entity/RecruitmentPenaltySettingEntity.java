package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * F03.11 Phase 5b: チーム/組織のペナルティ設定エンティティ。
 * recruitment_penalty_settings テーブルに対応する。
 */
@Entity
@Table(name = "recruitment_penalty_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentPenaltySettingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = false;

    /** N 回 NO_SHOW でペナルティ発動。 */
    @Column(name = "threshold_count", nullable = false)
    private int thresholdCount = 3;

    /** 集計対象期間（日）。 */
    @Column(name = "threshold_period_days", nullable = false)
    private int thresholdPeriodDays = 180;

    /** ペナルティ有効期間（日）。 */
    @Column(name = "penalty_duration_days", nullable = false)
    private int penaltyDurationDays = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_scope", nullable = false, length = 20)
    private PenaltyApplyScope applyScope = PenaltyApplyScope.THIS_SCOPE_ONLY;

    @Column(name = "auto_no_show_detection", nullable = false)
    private boolean autoNoShowDetection = false;

    /** 異議申立可能期間（日）。 */
    @Column(name = "dispute_allowed_days", nullable = false)
    private int disputeAllowedDays = 14;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public RecruitmentPenaltySettingEntity(
            RecruitmentScopeType scopeType, Long scopeId) {
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }

    /** 設定を更新する。 */
    public void update(boolean enabled, int thresholdCount, int thresholdPeriodDays,
                       int penaltyDurationDays, PenaltyApplyScope applyScope,
                       boolean autoNoShowDetection, int disputeAllowedDays) {
        this.enabled = enabled;
        this.thresholdCount = thresholdCount;
        this.thresholdPeriodDays = thresholdPeriodDays;
        this.penaltyDurationDays = penaltyDurationDays;
        this.applyScope = applyScope;
        this.autoNoShowDetection = autoNoShowDetection;
        this.disputeAllowedDays = disputeAllowedDays;
    }
}
