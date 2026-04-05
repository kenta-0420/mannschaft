package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * オンボーディング進捗レスポンス（一覧用）。
 */
public record OnboardingProgressResponse(
        Long id,
        UserSummary user,
        String templateName,
        OnboardingProgressStatus status,
        Integer totalSteps,
        Integer completedSteps,
        BigDecimal completionRate,
        LocalDateTime deadlineAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {}
