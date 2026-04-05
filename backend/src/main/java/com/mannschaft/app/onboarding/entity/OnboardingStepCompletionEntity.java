package com.mannschaft.app.onboarding.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.onboarding.OnboardingCompletionType;
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
 * オンボーディングステップ完了エンティティ。
 */
@Entity
@Table(name = "onboarding_step_completions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OnboardingStepCompletionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long progressId;

    @Column(nullable = false)
    private Long stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OnboardingCompletionType completionType;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime completedAt = LocalDateTime.now();
}
