package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;

import java.time.LocalDateTime;

/**
 * オンボーディングスキップレスポンス。
 */
public record SkipProgressResponse(
        Long id,
        OnboardingProgressStatus status,
        LocalDateTime completedAt
) {}
