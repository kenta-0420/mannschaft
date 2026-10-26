package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingStepType;

/**
 * オンボーディングステップレスポンス。
 */
public record StepResponse(
        Long id,
        String title,
        String description,
        OnboardingStepType stepType,
        Long referenceId,
        String referenceUrl,
        Integer deadlineOffsetDays,
        Integer sortOrder
) {}
