package com.mannschaft.app.billing.beta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

/**
 * F20.3 ベータ特典: 付与条件マスタ（{@code beta_perk_criteria}）。
 *
 * <p><b>マスタ例外</b>（全テナント共通・書き込みはシスアド運用のみ・全シャード複製）ゆえ、複合自然キー
 * （{@code beta_phase}, {@code grant_kind}）を主キーとし UUID 化しない（CLAUDE.md 原則 6 例外・設計書 01 §0）。</p>
 *
 * <p>全指標（{@code min_active_days} / {@code min_membership_tenure_days} / {@code min_active_members}）は
 * NULL 可＝「機構として指標を固定し、有効化は運用値」。ただし全指標 NULL の「無条件付与」はシスアド CRUD の
 * バリデーション（{@code BETA_PERK_009}）で拒否する（設計書 01 §2・本骨格 PR ではマスタ CRUD は未実装）。</p>
 *
 * <p>設計書: docs/features/F20.3_beta_perks/01_data_model.md §2</p>
 */
@Entity
@Table(name = "beta_perk_criteria")
@IdClass(BetaPerkCriteriaId.class)
@Check(name = "chk_bpc_phase", constraints = "beta_phase BETWEEN 1 AND 4")
@Check(name = "chk_bpc_kind", constraints = "grant_kind IN ('INDIVIDUAL','TEAM_ORG')")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BetaPerkCriteriaEntity {

    /** ベータ段階（1〜4）。 */
    @Id
    @Column(name = "beta_phase", nullable = false)
    private Integer betaPhase;

    /** INDIVIDUAL / TEAM_ORG。 */
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_kind", nullable = false, length = 12)
    private GrantKind grantKind;

    /** activeDays の評価ウィンドウ（日）。 */
    @Column(name = "evaluation_window_days", nullable = false)
    private Integer evaluationWindowDays;

    /** アクティブ日数の下限。NULL=この指標を評価しない（F10.8 実装前は NULL 運用）。 */
    @Column(name = "min_active_days")
    private Integer minActiveDays;

    /** 所属経過日数の下限。NULL=評価しない。 */
    @Column(name = "min_membership_tenure_days")
    private Integer minMembershipTenureDays;

    /** アクティブ人数の下限（TEAM_ORG のみ意味を持つ）。NULL=評価しない。 */
    @Column(name = "min_active_members")
    private Integer minActiveMembers;

    /** false=このフェーズ × 種別の付与を停止（自動バッチ・手動とも）。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 少なくとも 1 つの指標が非 NULL か（無条件付与でないか・設計書 01 §2）。 */
    public boolean hasAnyMetric() {
        return minActiveDays != null || minMembershipTenureDays != null || minActiveMembers != null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
