package com.mannschaft.app.onboarding.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * オンボーディング進捗エンティティ。
 */
@Entity
@Table(name = "onboarding_progresses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OnboardingProgressEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OnboardingProgressStatus status = OnboardingProgressStatus.IN_PROGRESS;

    @Column(nullable = false)
    private Short totalSteps;

    @Column(nullable = false)
    @Builder.Default
    private Short completedSteps = 0;

    private LocalDateTime deadlineAt;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    private LocalDateTime lastRemindedAt;

    /**
     * 完了ステップ数をインクリメントする。
     */
    public void incrementCompletedSteps() {
        this.completedSteps = (short) (this.completedSteps + 1);
    }

    /**
     * 進捗をCOMPLETED状態に変更する。
     */
    public void markCompleted() {
        this.status = OnboardingProgressStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 進捗をSKIPPED状態に変更する。
     */
    public void markSkipped() {
        this.status = OnboardingProgressStatus.SKIPPED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 進捗をリセットする。
     */
    public void reset() {
        this.status = OnboardingProgressStatus.IN_PROGRESS;
        this.completedSteps = 0;
        this.completedAt = null;
    }

    /**
     * 最終リマインド日時を更新する。
     */
    public void updateLastRemindedAt() {
        this.lastRemindedAt = LocalDateTime.now();
    }
}
