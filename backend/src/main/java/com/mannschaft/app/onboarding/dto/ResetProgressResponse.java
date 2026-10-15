package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;

import java.math.BigDecimal;

/**
 * オンボーディングリセットレスポンス。
 */
public record ResetProgressResponse(
        Long id,
        OnboardingProgressStatus status,
        Integer completedSteps,
        Integer totalSteps,
        BigDecimal completionRate
) {}
