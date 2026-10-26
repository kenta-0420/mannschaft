package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingCompletionType;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ステップ完了レスポンス。
 */
public record StepCompletionResponse(
        Long stepId,
        Boolean isCompleted,
        LocalDateTime completedAt,
        OnboardingCompletionType completionType,
        OnboardingProgressStatus progressStatus,
        Integer completedSteps,
        Integer totalSteps,
        BigDecimal completionRate
) {}
