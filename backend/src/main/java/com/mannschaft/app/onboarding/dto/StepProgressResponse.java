package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingCompletionType;
import com.mannschaft.app.onboarding.OnboardingStepType;

import java.time.LocalDateTime;

/**
 * オンボーディングステップ進捗レスポンス。
 */
public record StepProgressResponse(
        Long stepId,
        String title,
        String description,
        OnboardingStepType stepType,
        Long referenceId,
        String referenceUrl,
        Boolean isCompleted,
        LocalDateTime completedAt,
        OnboardingCompletionType completionType,
        LocalDateTime deadlineAt
) {}
